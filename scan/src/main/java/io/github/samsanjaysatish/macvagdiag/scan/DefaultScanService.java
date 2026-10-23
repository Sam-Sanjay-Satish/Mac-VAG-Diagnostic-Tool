package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ScanService}: validation and refusals on the caller's side, then one job per module on the
 * {@link RequestQueue}, so other callers can be served between modules of a long Auto-Scan. Created by
 * {@link ScanServiceFactory} (real stack) or by tests on a fake {@link ModuleConnector}.
 */
final class DefaultScanService implements ScanService {

    private static final Logger log = LoggerFactory.getLogger(DefaultScanService.class);

    /** UNCONFIRMED: which modules hold the VIN on this car (engine, instruments, gateway), asked in this order. */
    static final List<ModuleAddress> VEHICLE_INFO_MODULES =
            List.of(ModuleAddress.ENGINE, ModuleAddress.INSTRUMENTS, ModuleAddress.GATEWAY);
    /** Session start + DTC read: the least a fault-code read needs. A smaller window refuses up front. */
    static final int MIN_FAULT_READ_COST = 2;
    static final int KEPT_SCANS = 20;
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    static final String IGNITION_UNKNOWN = "not known: telling ignition on/off needs a request that isn't on the "
            + "approved read-only allowlist yet (open question). A module answering suggests it is on.";
    static final String ENGINE_UNKNOWN = "not known: engine speed needs a measuring-block or data-identifier read "
            + "that isn't on the approved read-only allowlist yet (open question).";

    private final ModuleConnector connector;
    private final TimeSource time;
    private final AuditLog audit;
    private final boolean demo;
    private final ScanEngine engine;
    private final RequestQueue queue = new RequestQueue();
    private final AtomicLong ids = new AtomicLong();
    private final Object scanLock = new Object();
    private AutoScanJob running; // guarded by scanLock
    private final Map<String, AutoScanJob> recent = new LinkedHashMap<>(); // guarded by scanLock
    private volatile boolean closed;

    DefaultScanService(ModuleConnector connector, TimeSource time, ScanLimits limits, AuditLog audit, boolean demo) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.time = Objects.requireNonNull(time, "time");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.demo = demo;
        this.engine = new ScanEngine(connector, time, Objects.requireNonNull(limits, "limits"), audit);
        connector.attach(engine);
        audit.service("start", demo ? "demo car (simulated, not a real vehicle)" : "CAN adapter");
    }

    ScanEngine engine() {
        return engine;
    }

    @Override
    public boolean isDemo() {
        return demo;
    }

    private void requireOpen() {
        if (closed) {
            throw ScanRefusedException.serviceClosed();
        }
    }

    private ScanContext newContext(Caller caller, String operation) {
        return new ScanContext(operation + "-" + ids.incrementAndGet(), caller, operation, () -> closed);
    }

    @Override
    public SessionStatus sessionStatus(Caller caller) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
        ModuleConnector.AdapterCheck a = queue.call(engine::checkAdapter);
        return new SessionStatus(a.connected() ? SessionStatus.AdapterState.CONNECTED : SessionStatus.AdapterState.DISCONNECTED,
                a.detail(), SessionStatus.VehicleCondition.unknown(IGNITION_UNKNOWN),
                SessionStatus.VehicleCondition.unknown(ENGINE_UNKNOWN), demo, time.now());
    }

    @Override
    public List<ModuleAvailability> listModules(Caller caller) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
        Optional<List<ModuleAvailability>> cached = engine.health().cachedListing();
        if (cached.isPresent()) {
            return cached.get();
        }
        ScanContext ctx = newContext(caller, "list-modules");
        queue.call(() -> {
            engine.requireAdapter(ctx);
            engine.requireWindowRoom(ctx, null, 1);
            return null;
        });
        List<ModuleAvailability> out = new ArrayList<>();
        for (ModuleAddress m : ModuleAddress.known()) {
            out.add(queue.call(() -> {
                engine.requireAdapter(ctx);
                return engine.probe(ctx, m);
            }));
        }
        if (out.stream().noneMatch(a -> a.reachability() == ModuleAvailability.Reachability.NOT_CHECKED)) {
            engine.health().cacheListing(out);
        }
        return List.copyOf(out);
    }

    @Override
    public ModuleResult readFaultCodes(Caller caller, ModuleAddress module) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(module, "module");
        requireOpen();
        ScanContext ctx = newContext(caller, "read-fault-codes");
        return queue.call(() -> {
            engine.requireAdapter(ctx);
            engine.requireNotBackingOff(ctx, module);
            engine.requireWindowRoom(ctx, module, MIN_FAULT_READ_COST);
            return engine.readModule(ctx, module, false);
        });
    }

    @Override
    public VehicleInfo vehicleInfo(Caller caller) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
        ScanContext ctx = newContext(caller, "vehicle-info");
        queue.call(() -> {
            engine.requireAdapter(ctx);
            engine.requireWindowRoom(ctx, null, 1);
            return null;
        });
        List<ModuleIdentity> modules = new ArrayList<>();
        for (ModuleAddress m : VEHICLE_INFO_MODULES) {
            modules.add(queue.call(() -> {
                engine.requireAdapter(ctx);
                return engine.identify(ctx, m);
            }));
        }
        Optional<ModuleIdentity> withVin = modules.stream()
                .filter(m -> m.identification().flatMap(EcuInfo::vin).isPresent()).findFirst();
        return new VehicleInfo(withVin.flatMap(m -> m.identification().flatMap(EcuInfo::vin)),
                withVin.map(ModuleIdentity::module), modules, time.now(), demo);
    }

    @Override
    public AutoScanJob startAutoScan(Caller caller, ScanProgressListener listener) {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
        AutoScanJob job;
        synchronized (scanLock) {
            if (running != null && !running.isDone()) {
                ScanRefusedException e = ScanRefusedException.scanInProgress(running.id());
                audit.refused(caller, null, null, "scan-in-progress", null, e.getMessage());
                throw e;
            }
            job = new AutoScanJob("scan-" + ids.incrementAndGet(), caller, time.now(), ModuleAddress.known().size(),
                    listener == null ? ScanProgressListener.NONE : listener);
            running = job;
            recent.put(job.id(), job);
            while (recent.size() > KEPT_SCANS) {
                recent.remove(recent.keySet().iterator().next());
            }
        }
        AutoScanJob started = job;
        Thread.ofPlatform().daemon().name("macvagdiag-" + job.id()).start(() -> runAutoScan(started));
        return job;
    }

    private void runAutoScan(AutoScanJob job) {
        ScanContext ctx = new ScanContext(job.id(), job.caller(), "auto-scan", () -> closed || job.cancelRequested());
        try {
            queue.call(() -> {
                engine.requireAdapter(ctx);
                engine.requireWindowRoom(ctx, null, MIN_FAULT_READ_COST);
                return null;
            });
            List<ModuleAddress> known = ModuleAddress.known();
            int total = known.size();
            audit.scan(ctx, "start", total + " modules");
            job.emit(new ScanEvent.Started(job.id(), total, time.now()));
            List<ModuleResult> results = new ArrayList<>();
            String stopReason = null;
            LimitHit stopLimit = null;
            for (int i = 0; i < total; i++) {
                ModuleAddress m = known.get(i);
                ModuleResult r;
                if (ctx.cancelled()) {
                    r = ScanEngine.skipped(m, "Auto-Scan cancelled", null, time.now());
                } else if (stopReason != null) {
                    r = ScanEngine.skipped(m, stopReason, stopLimit, time.now());
                } else {
                    job.emit(new ScanEvent.ModuleStarted(job.id(), i + 1, total, m, time.now()));
                    try {
                        r = queue.call(() -> engine.scanModule(ctx, m));
                    } catch (ScanRefusedException e) {
                        if (e.reason() == ScanRefusedException.Reason.SERVICE_CLOSED) {
                            throw e;
                        }
                        stopReason = e.getMessage();
                        r = ScanEngine.skipped(m, stopReason, null, time.now());
                    }
                    Optional<LimitHit> hit = r.limitHit().filter(h -> h.limit() == Limit.REQUESTS_PER_MINUTE);
                    if (hit.isPresent() && stopReason == null) {
                        stopLimit = hit.get();
                        stopReason = "Auto-Scan stopped: " + stopLimit.message();
                    }
                }
                results.add(r);
                job.emit(new ScanEvent.ModuleFinished(job.id(), i + 1, total, r, time.now()));
            }
            List<String> notes = new ArrayList<>();
            if (stopReason != null) {
                notes.add(stopReason);
            }
            Optional<ModuleResult> withVin = vinSource(results);
            AutoScanResult result = new AutoScanResult(job.id(), job.caller(), job.startedAt(), time.now(),
                    withVin.flatMap(m -> m.identification().flatMap(EcuInfo::vin)), withVin.map(ModuleResult::module),
                    results, ctx.cancelled(), demo, notes);
            if (!result.cancelled() && stopReason == null) {
                engine.health().cacheListing(results.stream().map(this::availability).toList());
            }
            audit.scan(ctx, "finish", result.respondingModules().size() + " responding, " + result.faultCount()
                    + " fault code(s)" + (result.cancelled() ? ", cancelled" : ""));
            job.complete(result);
        } catch (ScanRefusedException e) {
            job.fail(e, time.now());
        } catch (InterruptedException e) {
            job.fail(ScanRefusedException.serviceClosed(), time.now());
        } catch (RuntimeException e) {
            log.error("Auto-Scan {} failed", job.id(), e);
            job.fail(e, time.now());
        }
    }

    /** The module the VIN comes from: the engine first, then the instruments, gateway, then any other. */
    private static Optional<ModuleResult> vinSource(List<ModuleResult> results) {
        List<ModuleResult> withVin = results.stream()
                .filter(r -> r.identification().flatMap(EcuInfo::vin).isPresent()).toList();
        for (ModuleAddress preferred : VEHICLE_INFO_MODULES) {
            for (ModuleResult r : withVin) {
                if (r.module().equals(preferred)) {
                    return Optional.of(r);
                }
            }
        }
        return withVin.stream().findFirst();
    }

    private ModuleAvailability availability(ModuleResult r) {
        return new ModuleAvailability(r.module(), r.responded() ? ModuleAvailability.Reachability.RESPONDING
                : ModuleAvailability.Reachability.NOT_RESPONDING, r.link(),
                r.responded() ? "responds on " + r.link().map(ModuleLink::describe).orElse("?")
                        : String.join("; ", r.notes()), r.finishedAt());
    }

    @Override
    public Optional<AutoScanJob> findAutoScan(String scanId) {
        synchronized (scanLock) {
            return Optional.ofNullable(recent.get(scanId));
        }
    }

    @Override
    public void recordToolCall(ToolCallRecord call) {
        Objects.requireNonNull(call, "call");
        requireOpen();
        audit.toolCall(call);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        AutoScanJob job;
        synchronized (scanLock) {
            job = running;
        }
        if (job != null) {
            job.cancel();
        }
        queue.shutdownNow();
        try {
            if (!queue.awaitTermination(CLOSE_TIMEOUT)) {
                log.warn("The scan worker did not stop within {}; closing the adapter anyway", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            audit.service("stop", "closed");
        } catch (RuntimeException e) {
            log.warn("Could not write the audit log's stop record", e);
        }
        connector.close();
        try {
            audit.close();
        } catch (RuntimeException e) {
            log.warn("Could not close the audit log", e);
        }
    }
}
