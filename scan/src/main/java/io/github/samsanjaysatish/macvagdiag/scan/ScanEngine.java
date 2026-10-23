package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.DiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.Dtc;
import io.github.samsanjaysatish.macvagdiag.diag.DtcList;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.RawBytes;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker-side logic: connect (trying each link), read a module, probe, identify. Every method here runs on
 * the single worker thread of {@link RequestQueue}, so the adapter sees one request at a time.
 *
 * <p>It is also the {@link RequestObserver}: the transport tells it about every request right before sending. It
 * writes the audit line and checks the request was reserved by the current operation; a request outside any
 * reserved operation is refused (it throws, so the transport sends nothing).
 */
final class ScanEngine implements RequestObserver {

    private static final Logger log = LoggerFactory.getLogger(ScanEngine.class);
    private static final long NEVER = Long.MIN_VALUE;

    /** Thrown (from the audit hook) to stop a request that no operation reserved. */
    static final class UnreservedRequestException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        UnreservedRequestException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface Reserved<T> {
        T run() throws InterruptedException;
    }

    /** An opened module: the session (null if nothing answered) and what failed on the way. */
    record Opened(ModuleSession session, DiagResult<RawBytes> sessionResult, List<String> failures) {
    }

    private final ModuleConnector connector;
    private final TimeSource time;
    private final ScanLimits limits;
    private final AuditLog audit;
    private final RequestBudget budget;
    private final ModuleHealth health;

    // Worker-thread state.
    private long lastSendEnd = NEVER;
    private ScanContext callContext;
    private ModuleAddress callModule;
    private int callReserved;
    private int callSent;
    private Boolean lastAdapterConnected;

    ScanEngine(ModuleConnector connector, TimeSource time, ScanLimits limits, AuditLog audit) {
        this.connector = connector;
        this.time = time;
        this.limits = limits;
        this.audit = audit;
        this.budget = new RequestBudget(time, limits);
        this.health = new ModuleHealth(time, limits);
    }

    TimeSource time() {
        return time;
    }

    ScanLimits limits() {
        return limits;
    }

    AuditLog audit() {
        return audit;
    }

    ModuleHealth health() {
        return health;
    }

    RequestBudget budget() {
        return budget;
    }

    // ------------------------------------------------------------------ pacing and budgets

    long nextSendAllowedAt() {
        return lastSendEnd == NEVER ? NEVER : lastSendEnd + limits.minRequestGap().toNanos();
    }

    void markSent() {
        lastSendEnd = time.nanoTime();
    }

    private void waitForGap() throws InterruptedException {
        long at = nextSendAllowedAt();
        if (at != NEVER) {
            time.sleepUntil(at);
        }
    }

    void reserve(ScanContext ctx, ModuleAddress module, int cost) throws LimitReachedException {
        try {
            budget.reserve(ctx, module, cost);
        } catch (LimitReachedException e) {
            audit.refused(ctx.caller, ctx.id, module, "limit", e.hit(), e.getMessage());
            throw e;
        }
    }

    /** Runs {@code body} with {@code cost} requests reserved; the audit hook allows at most that many. */
    <T> T runReserved(ScanContext ctx, ModuleAddress module, int cost, Reserved<T> body) throws InterruptedException {
        callContext = ctx;
        callModule = module;
        callReserved = cost;
        callSent = 0;
        try {
            return body.run();
        } finally {
            callContext = null;
            callModule = null;
            callReserved = 0;
            callSent = 0;
        }
    }

    @Override
    public void sending(String link, String protocol, String requestHex) {
        if (callContext == null || callSent >= callReserved) {
            String why = callContext == null ? "a request outside any scan operation"
                    : "more requests than the operation reserved (" + callReserved + "); update DiagOp costs";
            audit.refused(callContext == null ? null : callContext.caller,
                    callContext == null ? null : callContext.id, callModule, "unreserved", null,
                    "stopped " + protocol + " request " + requestHex + ": " + why);
            throw new UnreservedRequestException("Not sending " + requestHex + ": " + why);
        }
        callSent++;
        audit.requestSent(callContext, callModule, link, protocol, requestHex); // throws: then it isn't sent
    }

    @Override
    public void rejected(String link, String protocol, String requestHex, String reason) {
        audit.rejected(callContext, callModule, link, protocol, requestHex, reason);
    }

    // ------------------------------------------------------------------ adapter

    ModuleConnector.AdapterCheck checkAdapter() {
        ModuleConnector.AdapterCheck a = connector.checkAdapter();
        if (lastAdapterConnected == null || lastAdapterConnected != a.connected()) {
            lastAdapterConnected = a.connected();
            audit.service(a.connected() ? "adapter-connected" : "adapter-disconnected", a.detail());
        }
        return a;
    }

    void requireAdapter(ScanContext ctx) {
        ModuleConnector.AdapterCheck a = checkAdapter();
        if (!a.connected()) {
            ScanRefusedException e = ScanRefusedException.adapterNotConnected(a.detail());
            audit.refused(ctx.caller, ctx.id, null, "adapter", null, e.getMessage());
            throw e;
        }
    }

    void requireNotBackingOff(ScanContext ctx, ModuleAddress module) {
        Optional<java.time.Duration> left = health.backoffRemaining(module);
        if (left.isPresent()) {
            ScanRefusedException e = ScanRefusedException.moduleNotResponding(module, left.get(), health.lastFailure(module));
            audit.refused(ctx.caller, ctx.id, module, "backoff",
                    new LimitHit(Limit.MODULE_NOT_RESPONDING_BACKOFF, left.get(), e.getMessage()), e.getMessage());
            throw e;
        }
    }

    /** Throws (and audits) if the one-minute window can't take {@code cost} more requests. */
    void requireWindowRoom(ScanContext ctx, ModuleAddress module, int cost) {
        Optional<LimitHit> hit = budget.checkWindow(cost);
        if (hit.isPresent()) {
            audit.refused(ctx.caller, ctx.id, module, "limit", hit.get(), hit.get().message());
            throw ScanRefusedException.limitExceeded(hit.get());
        }
    }

    void noticeAdapterFailure(DiagResult<?> r) {
        if (DiagMapper.isAdapterFailure(r)) {
            connector.adapterFailed(r.message());
        }
    }

    // ------------------------------------------------------------------ opening

    /**
     * Tries each link of {@code module} (the one that answered last first), {@link ScanLimits#connectAttempts()}
     * setups each, then starts the session. The module counts as present once the session request is answered
     * (positively or not); for ISO-TP, which sends nothing at open, that is the first sign of life.
     */
    Opened open(ScanContext ctx, ModuleAddress module) throws InterruptedException, LimitReachedException {
        List<String> failures = new ArrayList<>();
        for (ModuleLink link : health.linkOrder(module)) {
            ModuleConnector.ModuleConnection connection = null;
            for (int attempt = 1; attempt <= limits.connectAttempts() && connection == null; attempt++) {
                if (attempt > 1) {
                    time.sleepUntil(time.deadlineAfter(limits.backoff(attempt - 1)));
                }
                waitForGap();
                try {
                    connection = connector.connect(link);
                    audit.connect(ctx, module, link, attempt, "open", null);
                } catch (ModuleConnector.ConnectFailedException e) {
                    audit.connect(ctx, module, link, attempt, e.kind().name().toLowerCase(java.util.Locale.ROOT),
                            e.getMessage());
                    if (e.kind() == ModuleConnector.ConnectFailedException.Kind.ADAPTER) {
                        connector.adapterFailed(e.getMessage());
                        throw ScanRefusedException.adapterNotConnected(e.getMessage());
                    }
                    failures.add(link.describe() + ": " + e.getMessage());
                    if (e.kind() != ModuleConnector.ConnectFailedException.Kind.NO_RESPONSE) {
                        break; // refused or malformed: another setup won't help
                    }
                } finally {
                    markSent();
                }
            }
            if (connection == null) {
                continue;
            }
            ModuleSession session = new ModuleSession(this, ctx, module, connection);
            boolean keep = false;
            try {
                DiagResult<RawBytes> r = session.startSession();
                if (DiagMapper.answered(r)) {
                    keep = true;
                    health.markResponding(module, link);
                    return new Opened(session, r, failures);
                }
                failures.add(link.describe() + ": session start " + r.message());
            } finally {
                if (!keep) {
                    session.close();
                }
            }
        }
        return new Opened(null, null, failures);
    }

    private String noResponse(List<String> failures) {
        // The same reason from each setup attempt is said once.
        return failures.isEmpty() ? "no response"
                : "no response (" + String.join("; ", new java.util.LinkedHashSet<>(failures)) + ")";
    }

    // ------------------------------------------------------------------ operations

    /** Auto-Scan of one module: back-off and window checks, then {@link #readModule} with identification. */
    ModuleResult scanModule(ScanContext ctx, ModuleAddress module) throws InterruptedException {
        requireAdapter(ctx);
        Instant now = time.now();
        Optional<java.time.Duration> backoff = health.backoffRemaining(module);
        if (backoff.isPresent()) {
            return new ModuleResult(module, ModuleStatus.NOT_RESPONDING, Optional.empty(), Optional.empty(), List.of(),
                    false, List.of(health.lastFailure(module) + "; not asked again (it did not respond moments ago)"),
                    Optional.empty(), 0, now, now);
        }
        Optional<LimitHit> hit = budget.checkWindow(DefaultScanService.MIN_FAULT_READ_COST);
        if (hit.isPresent()) {
            audit.refused(ctx.caller, ctx.id, module, "limit", hit.get(), hit.get().message());
            return skipped(module, hit.get().message(), hit.get(), now);
        }
        return readModule(ctx, module, true);
    }

    static ModuleResult skipped(ModuleAddress module, String why, LimitHit hit, Instant at) {
        return new ModuleResult(module, ModuleStatus.SKIPPED, Optional.empty(), Optional.empty(), List.of(), false,
                List.of(why), Optional.ofNullable(hit), 0, at, at);
    }

    /** Opens the module, optionally reads identification, then fault codes and their freeze frames; always closes. */
    ModuleResult readModule(ScanContext ctx, ModuleAddress module, boolean withIdentification) throws InterruptedException {
        Instant started = time.now();
        List<String> notes = new ArrayList<>();
        Opened opened;
        try {
            opened = open(ctx, module);
        } catch (LimitReachedException e) {
            return new ModuleResult(module, ModuleStatus.SKIPPED, Optional.empty(), Optional.empty(), List.of(), false,
                    List.of(e.getMessage()), Optional.of(e.hit()), ctx.requests(module), started, time.now());
        }
        if (opened.session() == null) {
            String why = noResponse(opened.failures());
            health.markNotResponding(module, why);
            return new ModuleResult(module, ModuleStatus.NOT_RESPONDING, Optional.empty(), Optional.empty(), List.of(),
                    false, List.of(why), Optional.empty(), ctx.requests(module), started, time.now());
        }
        ModuleSession s = opened.session();
        Optional<EcuInfo> identification = Optional.empty();
        List<FaultCode> faults = new ArrayList<>();
        boolean complete = false;
        LimitHit limitHit = null;
        ModuleStatus status = ModuleStatus.ERROR;
        try {
            if (!opened.sessionResult().isSuccess()) {
                notes.add("session start: " + opened.sessionResult().message());
            }
            if (withIdentification && !ctx.cancelled()) {
                DiagResult<EcuIdentification> r = s.call(DiagOp.READ_IDENTIFICATION, DiagnosticService::readIdentification);
                if (r instanceof DiagResult.Success<EcuIdentification> ok) {
                    identification = Optional.of(DiagMapper.ecuInfo(ok.value()));
                } else {
                    notes.add("identification not read: " + r.message());
                }
            }
            if (ctx.cancelled()) {
                status = ModuleStatus.INCOMPLETE;
                notes.add("cancelled before the fault codes were read");
            } else {
                DiagResult<DtcList> d = s.call(DiagOp.READ_DTCS, DiagnosticService::readDtcs);
                if (d instanceof DiagResult.Success<DtcList> ok) {
                    DtcList list = ok.value();
                    complete = list.complete();
                    notes.addAll(list.warnings());
                    for (Dtc dtc : list.dtcs()) {
                        FreezeFrameData ff;
                        if (limitHit != null) {
                            ff = FreezeFrameData.skipped(limitHit.message());
                        } else if (ctx.cancelled()) {
                            ff = FreezeFrameData.skipped("cancelled");
                        } else if (s.abandoned()) {
                            ff = FreezeFrameData.skipped("module " + s.abandonReason());
                        } else {
                            try {
                                ff = DiagMapper.freezeFrame(s.call(DiagOp.READ_FREEZE_FRAME, x -> x.readFreezeFrame(dtc)));
                            } catch (LimitReachedException e) {
                                limitHit = e.hit();
                                ff = FreezeFrameData.skipped(e.getMessage());
                            }
                        }
                        faults.add(DiagMapper.faultCode(dtc, ff));
                    }
                    status = faults.isEmpty() ? ModuleStatus.OK : ModuleStatus.FAULTS;
                } else {
                    notes.add("fault codes not read: " + d.message());
                    status = s.abandoned() ? ModuleStatus.NOT_RESPONDING : ModuleStatus.ERROR;
                }
            }
        } catch (LimitReachedException e) {
            limitHit = e.hit();
            notes.add(e.getMessage());
            status = ModuleStatus.INCOMPLETE;
        } catch (UncheckedIOException | ScanRefusedException e) {
            throw e; // audit log broken, or the adapter is gone: stop the whole operation
        } catch (RuntimeException e) {
            log.error("Error while reading module {}", module, e);
            notes.add("internal error, module abandoned: " + e);
            status = ModuleStatus.ERROR;
        } finally {
            s.close();
        }
        if (s.abandoned()) {
            health.markNotResponding(module, s.abandonReason());
            notes.add("module " + s.abandonReason());
        }
        return new ModuleResult(module, status, Optional.of(s.link()), identification, faults, complete, notes,
                Optional.ofNullable(limitHit), ctx.requests(module), started, time.now());
    }

    /** Opens and closes the module: does it answer? */
    ModuleAvailability probe(ScanContext ctx, ModuleAddress module) throws InterruptedException {
        Optional<java.time.Duration> backoff = health.backoffRemaining(module);
        if (backoff.isPresent()) {
            return new ModuleAvailability(module, ModuleAvailability.Reachability.NOT_RESPONDING, Optional.empty(),
                    health.lastFailure(module) + " (checked moments ago)", time.now());
        }
        try {
            Opened o = open(ctx, module);
            if (o.session() == null) {
                String why = noResponse(o.failures());
                health.markNotResponding(module, why);
                return new ModuleAvailability(module, ModuleAvailability.Reachability.NOT_RESPONDING, Optional.empty(),
                        why, time.now());
            }
            o.session().close();
            return new ModuleAvailability(module, ModuleAvailability.Reachability.RESPONDING,
                    Optional.of(o.session().link()), "responds on " + o.session().link().describe(), time.now());
        } catch (LimitReachedException e) {
            return new ModuleAvailability(module, ModuleAvailability.Reachability.NOT_CHECKED, Optional.empty(),
                    e.getMessage(), time.now());
        }
    }

    /** Opens the module and reads its identification. */
    ModuleIdentity identify(ScanContext ctx, ModuleAddress module) throws InterruptedException {
        Optional<java.time.Duration> backoff = health.backoffRemaining(module);
        if (backoff.isPresent()) {
            return new ModuleIdentity(module, ModuleAvailability.Reachability.NOT_RESPONDING, Optional.empty(),
                    Optional.empty(), List.of(health.lastFailure(module) + " (checked moments ago)"));
        }
        Opened o;
        try {
            o = open(ctx, module);
        } catch (LimitReachedException e) {
            return new ModuleIdentity(module, ModuleAvailability.Reachability.NOT_CHECKED, Optional.empty(),
                    Optional.empty(), List.of(e.getMessage()));
        }
        if (o.session() == null) {
            String why = noResponse(o.failures());
            health.markNotResponding(module, why);
            return new ModuleIdentity(module, ModuleAvailability.Reachability.NOT_RESPONDING, Optional.empty(),
                    Optional.empty(), List.of(why));
        }
        ModuleSession s = o.session();
        try {
            DiagResult<EcuIdentification> r = s.call(DiagOp.READ_IDENTIFICATION, DiagnosticService::readIdentification);
            if (r instanceof DiagResult.Success<EcuIdentification> ok) {
                return new ModuleIdentity(module, ModuleAvailability.Reachability.RESPONDING, Optional.of(s.link()),
                        Optional.of(DiagMapper.ecuInfo(ok.value())), List.of());
            }
            return new ModuleIdentity(module, ModuleAvailability.Reachability.RESPONDING, Optional.of(s.link()),
                    Optional.empty(), List.of("identification not read: " + r.message()));
        } catch (LimitReachedException e) {
            return new ModuleIdentity(module, ModuleAvailability.Reachability.RESPONDING, Optional.of(s.link()),
                    Optional.empty(), List.of(e.getMessage()));
        } finally {
            s.close();
        }
    }
}
