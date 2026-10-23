package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticSession;
import io.github.samsanjaysatish.macvagdiag.diag.RawBytes;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;

/**
 * One open channel and diagnostic session to one module, on the worker thread. It owns:
 * <ul>
 *   <li><b>Pacing:</b> waits for {@link ScanLimits#minRequestGap()} since the last exchange with the car.</li>
 *   <li><b>Keep-alive:</b> while it waits (pacing, back-off), it sends TP 2.0 keep-alive after
 *       {@link ScanLimits#channelKeepAliveInterval()} and Tester Present after
 *       {@link ScanLimits#testerPresentInterval()} of idleness, scheduled on the {@link TimeSource}. Nothing outside
 *       this class can send either; both stop when the session is closed.</li>
 *   <li><b>Retries:</b> a timeout or {@code 7F xx 21} (busy) is retried up to {@link ScanLimits#maxRetries()} times
 *       with exponential back-off.</li>
 *   <li><b>Giving up:</b> after {@link ScanLimits#maxConsecutiveFailures()} failed operations in a row, or a lost
 *       channel, nothing more is sent to this module.</li>
 *   <li><b>Budgets:</b> every request is reserved first ({@link ScanEngine#reserve}).</li>
 * </ul>
 * Always closed by its creator in a {@code finally} block.
 */
final class ModuleSession implements AutoCloseable {

    /** A {@link DiagnosticService} call. */
    @FunctionalInterface
    interface DiagCall<T> {
        DiagResult<T> run(DiagnosticService diag) throws InterruptedException;
    }

    /** UNCONFIRMED: VCDS's session per protocol. KWP: the VW diagnostic session {@code 10 89}. */
    static final DiagnosticSession KWP_SESSION = DiagnosticSession.KWP_VW_DIAGNOSTIC;
    /**
     * UNCONFIRMED: UDS default session {@code 10 01}, the least state change: reading DTCs and DIDs is allowed in
     * the default session, and it doesn't time out. VCDS may use {@code 10 03}; check the capture.
     */
    static final DiagnosticSession UDS_SESSION = DiagnosticSession.UDS_DEFAULT;

    private final ScanEngine engine;
    private final ScanContext ctx;
    private final ModuleAddress module;
    private final ModuleLink link;
    private final ModuleConnector.ModuleConnection connection;
    private final TimeSource time;
    private final ScanLimits limits;

    private long lastDiagActivity;
    private long lastChannelActivity;
    private boolean sessionEstablished;
    private boolean abandoned;
    private String abandonReason = "";
    private int consecutiveFailures;
    private boolean closed;

    ModuleSession(ScanEngine engine, ScanContext ctx, ModuleAddress module, ModuleConnector.ModuleConnection connection) {
        this.engine = engine;
        this.ctx = ctx;
        this.module = module;
        this.link = connection.link();
        this.connection = connection;
        this.time = engine.time();
        this.limits = engine.limits();
        long now = time.nanoTime();
        this.lastDiagActivity = now;
        this.lastChannelActivity = now;
    }

    ModuleLink link() {
        return link;
    }

    ApplicationProtocol protocol() {
        return connection.diagnostics().protocol();
    }

    boolean abandoned() {
        return abandoned;
    }

    String abandonReason() {
        return abandonReason;
    }

    /** {@code 10 89} (KWP) or {@code 10 01} (UDS). Tester Present only runs once this succeeded. */
    DiagResult<RawBytes> startSession() throws InterruptedException, LimitReachedException {
        DiagnosticSession session = protocol() == ApplicationProtocol.KWP2000 ? KWP_SESSION : UDS_SESSION;
        DiagResult<RawBytes> r = call(DiagOp.START_SESSION, d -> d.startSession(session));
        sessionEstablished = r.isSuccess();
        return r;
    }

    /**
     * Runs one operation with pacing, keep-alive, budget, retries and back-off. Returns
     * {@link DiagResult.NotAvailable} (nothing sent) once the module has been given up.
     */
    <T> DiagResult<T> call(DiagOp op, DiagCall<T> fn) throws InterruptedException, LimitReachedException {
        int attempt = 0;
        while (true) {
            if (abandoned) {
                return new DiagResult.NotAvailable<>("not sent: module " + module.hex() + " " + abandonReason);
            }
            prepareToSend();
            DiagResult<T> r = send(op, attempt, fn);
            boolean retryable = r instanceof DiagResult.Busy<?> || r instanceof DiagResult.TimedOut<?>;
            if (retryable && attempt < limits.maxRetries() && connection.isOpen() && !ctx.cancelled()) {
                attempt++;
                idleUntil(time.deadlineAfter(limits.backoff(attempt)));
                continue;
            }
            track(r);
            return r;
        }
    }

    private <T> DiagResult<T> send(DiagOp op, int attempt, DiagCall<T> fn) throws InterruptedException, LimitReachedException {
        DiagnosticService diag = connection.diagnostics();
        int cost = op.cost(diag.protocol());
        engine.reserve(ctx, module, cost);
        DiagResult<T> r;
        try {
            r = engine.runReserved(ctx, module, cost, () -> fn.run(diag));
        } finally {
            markActivity();
        }
        engine.audit().result(ctx, module, op, attempt, r);
        engine.noticeAdapterFailure(r);
        return r;
    }

    private void track(DiagResult<?> r) {
        if (!DiagMapper.isFailure(r)) {
            consecutiveFailures = 0;
            return;
        }
        consecutiveFailures++;
        if (!connection.isOpen()) {
            abandon("lost the channel: " + r.message());
        } else if (consecutiveFailures >= limits.maxConsecutiveFailures()) {
            abandon("stopped responding after " + consecutiveFailures + " failed operations in a row (last: "
                    + r.message() + ")");
        }
    }

    private void abandon(String why) {
        if (!abandoned) {
            abandoned = true;
            abandonReason = why;
        }
    }

    private void markActivity() {
        long now = time.nanoTime();
        lastDiagActivity = now;
        lastChannelActivity = now;
        engine.markSent();
    }

    // ------------------------------------------------------------------ waiting and keep-alive

    /** Waits for the pacing gap; sends a due keep-alive first if there is one. */
    private void prepareToSend() throws InterruptedException {
        idleUntil(engine.nextSendAllowedAt());
        if (nextKeepAliveDue() <= time.nanoTime()) {
            keepAliveIfDue();
            idleUntil(engine.nextSendAllowedAt());
        }
    }

    /** Waits until {@code deadline} on the {@link TimeSource}, keeping the channel and session alive meanwhile. */
    void idleUntil(long deadline) throws InterruptedException {
        while (true) {
            long now = time.nanoTime();
            if (now >= deadline) {
                return;
            }
            long due = nextKeepAliveDue();
            if (due <= now) {
                keepAliveIfDue();
                continue;
            }
            time.sleepUntil(Math.min(deadline, due));
        }
    }

    private long nextKeepAliveDue() {
        if (closed || abandoned || !connection.isOpen()) {
            return Long.MAX_VALUE;
        }
        long due = Long.MAX_VALUE;
        if (connection.hasChannelKeepAlive()) {
            due = lastChannelActivity + limits.channelKeepAliveInterval().toNanos();
        }
        if (sessionEstablished) {
            due = Math.min(due, lastDiagActivity + limits.testerPresentInterval().toNanos());
        }
        return due;
    }

    private void keepAliveIfDue() throws InterruptedException {
        long now = time.nanoTime();
        if (connection.hasChannelKeepAlive() && now >= lastChannelActivity + limits.channelKeepAliveInterval().toNanos()) {
            boolean alive;
            try {
                alive = connection.keepChannelAlive();
            } finally {
                lastChannelActivity = time.nanoTime();
                engine.markSent();
            }
            engine.audit().channelKeepAlive(ctx, module, alive);
            if (!alive) {
                abandon("lost the channel: no answer to the TP 2.0 keep-alive");
                return;
            }
        }
        if (sessionEstablished && !abandoned && connection.isOpen()
                && now >= lastDiagActivity + limits.testerPresentInterval().toNanos()) {
            try {
                track(send(DiagOp.TESTER_PRESENT, 0, DiagnosticService::testerPresent));
            } catch (LimitReachedException e) {
                lastDiagActivity = time.nanoTime(); // skipped: the operation after it will be refused too
            }
        }
    }

    /** Closes the channel; idempotent. No keep-alive is sent after this. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } finally {
            engine.markSent();
            engine.audit().closed(ctx, module);
        }
    }
}
