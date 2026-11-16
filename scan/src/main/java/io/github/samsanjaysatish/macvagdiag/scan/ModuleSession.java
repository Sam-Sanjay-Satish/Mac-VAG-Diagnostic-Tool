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
 *   <li><b>Keep-alive:</b> while it waits (pacing, back-off), it sends the TP 2.0 keep-alive ({@code A3}) after
 *       {@link ScanLimits#channelKeepAliveInterval()} of idleness on TP 2.0 channels, and Tester Present after
 *       {@link ScanLimits#testerPresentInterval()} on links without a channel keep-alive (UDS over ISO-TP),
 *       scheduled on the {@link TimeSource}. No KWP {@code 3E} on TP 2.0: VCDS never sent one on the 2026-11-13
 *       capture and kept every TP 2.0 session alive with {@code A3} alone (also through idle stretches of several
 *       seconds in the 01 fault-code read). Nothing outside this class can send either; both stop when the
 *       session is closed.</li>
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

    /** KWP: the VW diagnostic session {@code 10 89}, as VCDS sent to every TP 2.0 module (2026-11-13 capture). */
    static final DiagnosticSession KWP_SESSION = DiagnosticSession.KWP_VW_DIAGNOSTIC;
    /**
     * UDS: the extended session {@code 10 03}, because that is what VCDS used on every UDS module of the target
     * car (2026-11-13 capture: {@code 10 03} first, never {@code 10 01}), so it is the session known to work for
     * {@code 19 02 AE} / {@code 19 06} and the identification DIDs there. Whether the default session would do is
     * unknown (VCDS never tried). {@code 10 03} is a read-only diagnostic session (on the allowlist; programming
     * {@code 10 02} is not). It ends after 5 s (S3server) without a request; Tester Present every
     * {@link ScanLimits#testerPresentInterval()} keeps it open, and closing the channel just lets it time out
     * back to the default session.
     */
    static final DiagnosticSession UDS_SESSION = DiagnosticSession.UDS_EXTENDED;

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

    /** {@code 10 89} (KWP) or {@code 10 03} (UDS). Tester Present only runs once this succeeded. */
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
        if (sendsTesterPresent()) {
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
        if (sendsTesterPresent() && !abandoned && connection.isOpen()
                && now >= lastDiagActivity + limits.testerPresentInterval().toNanos()) {
            try {
                track(send(DiagOp.TESTER_PRESENT, 0, DiagnosticService::testerPresent));
            } catch (LimitReachedException e) {
                lastDiagActivity = time.nanoTime(); // skipped: the operation after it will be refused too
            }
        }
    }

    /**
     * Tester Present only once the session is up, and only on links without a channel keep-alive: on TP 2.0 the
     * {@code A3} keep-alive covers the session as well (VCDS, 2026-11-13 capture).
     */
    private boolean sendsTesterPresent() {
        return sessionEstablished && !connection.hasChannelKeepAlive();
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
