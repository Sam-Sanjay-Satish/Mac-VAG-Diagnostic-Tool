package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.labels.LabelLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The modules {@link #vehicleInfo} identifies, in this order; 17 also gives the mileage. */
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
    private final LiveDataEngine live;
    private final Optional<Path> recordingsDirectory;
    private final RequestQueue queue = new RequestQueue();
    private final AtomicLong ids = new AtomicLong();
    private final Object scanLock = new Object();
    private AutoScanJob running; // guarded by scanLock
    private final Map<String, AutoScanJob> recent = new LinkedHashMap<>(); // guarded by scanLock
    private volatile boolean closed;

    DefaultScanService(ModuleConnector connector, TimeSource time, ScanLimits limits, AuditLog audit, boolean demo) {
        this(connector, time, limits, audit, demo, LabelLibrary.empty(), Optional.empty());
    }

    /**
     * @param labels label files for live-data names and specs
     * @param recordingsDirectory where recordings' CSV files go; empty = not written
     */
    DefaultScanService(ModuleConnector connector, TimeSource time, ScanLimits limits, AuditLog audit, boolean demo,
            LabelLibrary labels, Optional<Path> recordingsDirectory) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.time = Objects.requireNonNull(time, "time");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.demo = demo;
        this.engine = new ScanEngine(connector, time, Objects.requireNonNull(limits, "limits"), audit);
        this.live = new LiveDataEngine(engine, Objects.requireNonNull(labels, "labels"));
        this.recordingsDirectory = Objects.requireNonNull(recordingsDirectory, "recordingsDirectory");
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
        ScanEngine.Discovery discovery = queue.call(() -> {
            engine.requireAdapter(ctx);
            return engine.discover(ctx);
        });
        Plan plan = Plan.of(discovery);
        List<ModuleAvailability> out = new ArrayList<>();
        for (ModuleAddress m : ModuleAddress.known()) {
            Optional<GatewayStatus> gs = plan.gatewayStatus(m);
            if (plan.fromList() && gs.isEmpty()) {
                out.add(notInstalled(m));
                continue;
            }
            ModuleAvailability a = queue.call(() -> {
                engine.requireAdapter(ctx);
                return engine.probe(ctx, m);
            });
            out.add(gs.map(a::withGatewayStatus).orElse(a));
        }
        // Cached unless a limit stopped a check (04 is always NOT_CHECKED: it has no link to check).
        if (out.stream().noneMatch(a -> a.reachability() == ModuleAvailability.Reachability.NOT_CHECKED
                && a.module().hasLink())) {
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
        Optional<Mileage> mileage = modules.stream().flatMap(m -> m.mileage().stream()).findFirst();
        return new VehicleInfo(mileage, modules, time.now(), demo);
    }

    @Override
    public LiveDataCatalog liveDataCatalog(Caller caller, ModuleAddress module) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(module, "module");
        requireOpen();
        ScanContext ctx = newContext(caller, "live-data-catalog");
        if (!LiveDataEngine.supported(module)) {
            return live.catalog(ctx, module); // nothing is sent
        }
        return queue.call(() -> {
            engine.requireAdapter(ctx);
            engine.requireNotBackingOff(ctx, module);
            engine.requireWindowRoom(ctx, module, MIN_FAULT_READ_COST);
            return live.catalog(ctx, module);
        });
    }

    private LiveDataEngine.Request validateLive(Caller caller, List<LiveValueId> values) {
        try {
            return live.validate(values);
        } catch (InvalidLiveValueException e) {
            ModuleAddress m = values == null || values.isEmpty() || values.getFirst() == null ? null
                    : values.getFirst().module();
            audit.refused(caller, null, m, "invalid-live-value", null, e.getMessage());
            throw e;
        }
    }

    @Override
    public LiveDataSnapshot readLiveData(Caller caller, List<LiveValueId> values) throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
        LiveDataEngine.Request req = validateLive(caller, values);
        ScanContext ctx = newContext(caller, "read-live-data");
        return queue.call(() -> {
            engine.requireAdapter(ctx);
            engine.requireNotBackingOff(ctx, req.module());
            engine.requireWindowRoom(ctx, req.module(), MIN_FAULT_READ_COST + req.groups().size());
            return live.read(ctx, req);
        });
    }

    @Override
    public LiveDataRecording recordLiveData(Caller caller, List<LiveValueId> values, Duration duration)
            throws InterruptedException {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
        try {
            LiveDataEngine.validateDuration(duration);
        } catch (InvalidLiveValueException e) {
            audit.refused(caller, null, null, "invalid-live-value", null, e.getMessage());
            throw e;
        }
        LiveDataEngine.Request req = validateLive(caller, values);
        ScanContext ctx = newContext(caller, "record-live-data");
        ctx.moduleBudget(LiveDataEngine.recordingBudget(duration));
        LiveDataEngine.RecordingRun run = queue.call(() -> {
            engine.requireAdapter(ctx);
            engine.requireNotBackingOff(ctx, req.module());
            engine.requireWindowRoom(ctx, req.module(), MIN_FAULT_READ_COST + req.groups().size());
            audit.scan(ctx, "record-start", req.module().hex() + ": " + req.ids() + " for " + duration.toSeconds() + " s");
            LiveDataEngine.RecordingRun r = live.record(ctx, req, duration);
            audit.scan(ctx, "record-finish", r.recording().rounds() + " rounds, " + r.recording().requests()
                    + " requests, " + r.recording().status());
            return r;
        });
        return withFile(run);
    }

    /** Writes the CSV (if there is a folder and something was recorded) and puts its path in the result. */
    private LiveDataRecording withFile(LiveDataEngine.RecordingRun run) {
        LiveDataRecording r = run.recording();
        if (recordingsDirectory.isEmpty() || r.rounds() == 0) {
            return r;
        }
        Path file = recordingsDirectory.get().resolve(r.id() + ".csv");
        List<String> notes = new ArrayList<>(r.notes());
        Optional<Path> written = Optional.empty();
        try {
            Files.createDirectories(recordingsDirectory.get());
            Files.writeString(file, run.csv(), StandardCharsets.UTF_8);
            written = Optional.of(file);
        } catch (IOException e) {
            log.warn("Could not write recording {}", file, e);
            notes.add("the CSV file could not be written (" + e.getMessage() + "); the summary is complete");
        }
        return new LiveDataRecording(r.id(), r.module(), r.status(), r.labelSource(), r.series(), r.rounds(),
                r.requested(), r.recorded(), written, notes, r.limitHit(), r.requests(), r.startedAt(), r.finishedAt());
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
            job = new AutoScanJob("scan-" + ids.incrementAndGet(), caller, time.now(), Plan.FIXED_LIST.size(),
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
            ScanEngine.Discovery discovery = ctx.cancelled() ? new ScanEngine.Discovery(Optional.empty(),
                    Optional.of("cancelled before the installation list was read")) : queue.call(() -> {
                        engine.requireAdapter(ctx);
                        return engine.discover(ctx);
                    });
            Plan plan = Plan.of(discovery);
            List<ModuleAddress> known = plan.modules();
            int total = known.size();
            job.planned(total);
            audit.scan(ctx, "start", total + " modules, " + (plan.fromList() ? "from the gateway's installation list"
                    : "fixed list"));
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
                Optional<GatewayStatus> gs = plan.gatewayStatus(m);
                if (gs.isPresent()) {
                    r = r.withGatewayStatus(gs.get());
                }
                results.add(r);
                job.emit(new ScanEvent.ModuleFinished(job.id(), i + 1, total, r, time.now()));
            }
            List<String> notes = new ArrayList<>(plan.notes());
            if (stopReason != null) {
                notes.add(stopReason);
            }
            Optional<Mileage> mileage = results.stream().flatMap(r -> r.mileage().stream()).findFirst();
            AutoScanResult result = new AutoScanResult(job.id(), job.caller(), job.startedAt(), time.now(), mileage,
                    results, ctx.cancelled(), demo, notes, plan.installationList());
            if (!result.cancelled() && stopReason == null) {
                engine.health().cacheListing(listing(results, plan));
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

    private ModuleAvailability availability(ModuleResult r) {
        ModuleAvailability.Reachability reach = r.responded() ? ModuleAvailability.Reachability.RESPONDING
                : r.status() == ModuleStatus.GATEWAY_STATUS_ONLY ? ModuleAvailability.Reachability.NOT_CHECKED
                : ModuleAvailability.Reachability.NOT_RESPONDING;
        return new ModuleAvailability(r.module(), reach, r.link(),
                r.responded() ? "responds on " + r.link().map(ModuleLink::describe).orElse("?")
                        : String.join("; ", r.notes()), r.finishedAt(), r.gatewayStatus());
    }

    private ModuleAvailability notInstalled(ModuleAddress m) {
        return new ModuleAvailability(m, ModuleAvailability.Reachability.NOT_INSTALLED, Optional.empty(),
                "not installed: the gateway's installation list doesn't list it; not contacted", time.now());
    }

    /** What {@link #listModules} would say after this Auto-Scan: every known module, scanned or not installed. */
    private List<ModuleAvailability> listing(List<ModuleResult> results, Plan plan) {
        List<ModuleAvailability> out = new ArrayList<>();
        for (ModuleAddress m : ModuleAddress.known()) {
            Optional<ModuleResult> r = results.stream().filter(x -> x.module().equals(m)).findFirst();
            out.add(r.map(this::availability).orElseGet(() -> notInstalled(m)));
        }
        return out;
    }

    /**
     * Which modules a scan contacts. With the gateway's installation list: the known modules it lists as
     * installed, in address order (04 included, answered from the list alone), plus notes for listed modules our
     * table doesn't have. Without it: every known module with a link (VCDS's own fallback is the same idea).
     *
     * @param modules the modules to check, in address order
     * @param installationList the gateway's status of every installed module, in list order (empty without a list)
     * @param notes readable notes for the scan
     */
    record Plan(List<ModuleAddress> modules, List<GatewayStatus> installationList, List<String> notes) {

        /** Every known module that can be contacted: the fallback. */
        static final List<ModuleAddress> FIXED_LIST = ModuleAddress.known().stream().filter(ModuleAddress::hasLink)
                .toList();

        Plan {
            modules = List.copyOf(modules);
            installationList = List.copyOf(installationList);
            notes = List.copyOf(notes);
        }

        static Plan of(ScanEngine.Discovery d) {
            List<String> notes = new ArrayList<>();
            if (d.list().isEmpty()) {
                notes.add("module list: the gateway's installation list was not read ("
                        + d.note().orElse("no reason given") + "); probed every known module instead");
                return new Plan(FIXED_LIST, List.of(), notes);
            }
            d.note().ifPresent(notes::add);
            List<GatewayStatus> listed = new ArrayList<>();
            for (io.github.samsanjaysatish.macvagdiag.diag.InstallationList.Entry e : d.list().get().installed()) {
                GatewayStatus gs = ScanEngine.gatewayStatus(e);
                listed.add(gs);
                if (gs.module().isEmpty()) {
                    notes.add(String.format("the gateway lists module %s (%s) as installed, but it isn't in our "
                            + "module table, so it was not scanned", gs.hex(), gs.vcdsText()));
                    continue;
                }
                ModuleAddress m = gs.module().get();
                for (ModuleLink link : m.links()) {
                    if (link instanceof ModuleLink.Tp20 t && e.tp20Address().isPresent()
                            && e.tp20Address().get() != t.logicalAddress()) {
                        notes.add(String.format("the gateway lists %s at TP 2.0 address %02X, our table has %02X; "
                                + "using ours", m.hex(), e.tp20LogicalAddress(), t.logicalAddress()));
                    }
                }
            }
            List<ModuleAddress> modules = ModuleAddress.known().stream()
                    .filter(m -> listed.stream().anyMatch(gs -> gs.module().equals(Optional.of(m)))).toList();
            notes.add(0, "module list: from the gateway's installation list (" + listed.size() + " installed)");
            return new Plan(modules, listed, notes);
        }

        boolean fromList() {
            return !installationList.isEmpty();
        }

        Optional<GatewayStatus> gatewayStatus(ModuleAddress m) {
            return installationList.stream().filter(gs -> gs.module().equals(Optional.of(m))).findFirst();
        }
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
