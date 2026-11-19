package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A scripted {@link ScanService} for front-end tests ({@code server} controllers, {@code mcp} tool handlers): no
 * queue, no car, no clock. Returns {@link SampleScanData} unless told otherwise, records every call, and can be made
 * to refuse everything with a given {@link ScanRefusedException} (adapter missing, rate limit, ...).
 *
 * <pre>{@code
 * FakeScanService scan = new FakeScanService();
 * scan.failWith(ScanRefusedException.adapterNotConnected("unplugged"));   // → 503 / MCP error text
 * scan.faults(ModuleAddress.parse("08"), myResult);
 * }</pre>
 *
 * Thread-safe. Get it with {@code testImplementation(testFixtures(project(":scan")))}.
 */
public final class FakeScanService implements ScanService {

    /** One call: method name, caller label, argument (module hex, scan ID or tool name) or empty. */
    public record Call(String method, String caller, String argument) {
    }

    private volatile SessionStatus status = SampleScanData.sessionStatus();
    private volatile List<ModuleAvailability> modules = SampleScanData.moduleList();
    private final Map<ModuleAddress, ModuleResult> faults = new ConcurrentHashMap<>();
    private volatile VehicleInfo vehicleInfo = SampleScanData.vehicleInfo();
    private volatile AutoScanResult autoScanResult = SampleScanData.autoScan();
    private volatile RuntimeException failure;
    private volatile boolean demo;
    private volatile boolean closed;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AutoScanJob> jobs = new ConcurrentHashMap<>();
    private final Map<ModuleAddress, LiveDataCatalog> catalogs = new ConcurrentHashMap<>();
    private final Map<ModuleAddress, LiveDataCatalog> listedCatalogs = new ConcurrentHashMap<>();

    public FakeScanService status(SessionStatus value) {
        status = Objects.requireNonNull(value);
        return this;
    }

    public FakeScanService modules(List<ModuleAvailability> value) {
        modules = List.copyOf(value);
        return this;
    }

    public FakeScanService faults(ModuleAddress module, ModuleResult value) {
        faults.put(module, value);
        return this;
    }

    public FakeScanService vehicleInfo(VehicleInfo value) {
        vehicleInfo = Objects.requireNonNull(value);
        return this;
    }

    public FakeScanService autoScanResult(AutoScanResult value) {
        autoScanResult = Objects.requireNonNull(value);
        return this;
    }

    /** Every call from now on throws {@code e} (e.g. a {@link ScanRefusedException}); null to stop. */
    public FakeScanService failWith(RuntimeException e) {
        failure = e;
        return this;
    }

    public FakeScanService demo(boolean value) {
        demo = value;
        return this;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<ToolCallRecord> toolCalls() {
        synchronized (toolCalls) {
            return List.copyOf(toolCalls);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void call(String method, Caller caller, String argument) {
        calls.add(new Call(method, caller == null ? "" : caller.label(), argument));
        if (closed) {
            throw ScanRefusedException.serviceClosed();
        }
        RuntimeException f = failure;
        if (f != null) {
            throw f;
        }
    }

    @Override
    public boolean isDemo() {
        return demo;
    }

    @Override
    public SessionStatus sessionStatus(Caller caller) {
        call("sessionStatus", caller, "");
        return status;
    }

    @Override
    public List<ModuleAvailability> listModules(Caller caller) {
        call("listModules", caller, "");
        return modules;
    }

    @Override
    public ModuleResult readFaultCodes(Caller caller, ModuleAddress module) {
        call("readFaultCodes", caller, module.hex());
        return faults.getOrDefault(module, SampleScanData.moduleResult(module));
    }

    @Override
    public VehicleInfo vehicleInfo(Caller caller) {
        call("vehicleInfo", caller, "");
        return vehicleInfo;
    }

    /** Completes at once with {@link #autoScanResult}, sending the usual progress events to {@code listener}. */
    @Override
    public AutoScanJob startAutoScan(Caller caller, ScanProgressListener listener) {
        call("startAutoScan", caller, "");
        AutoScanJob job = AutoScanJob.completed(autoScanResult, listener);
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public Optional<AutoScanJob> findAutoScan(String scanId) {
        call("findAutoScan", null, scanId);
        return Optional.ofNullable(jobs.get(scanId));
    }

    /** {@link SampleScanData#liveDataCatalog}; remembered, so reads are checked against it like the real service. */
    @Override
    public LiveDataCatalog liveDataCatalog(Caller caller, ModuleAddress module) {
        call("liveDataCatalog", caller, module.hex());
        LiveDataCatalog c = catalogs.getOrDefault(module, SampleScanData.liveDataCatalog(module));
        listedCatalogs.put(module, c);
        return c;
    }

    /** Replaces the sample catalog of {@code module}. */
    public FakeScanService liveDataCatalog(ModuleAddress module, LiveDataCatalog value) {
        catalogs.put(module, value);
        return this;
    }

    @Override
    public LiveDataSnapshot readLiveData(Caller caller, List<LiveValueId> values) {
        call("readLiveData", caller, values == null ? "" : values.toString());
        check(values);
        return SampleScanData.liveDataSnapshot(values);
    }

    @Override
    public LiveDataRecording recordLiveData(Caller caller, List<LiveValueId> values, java.time.Duration duration) {
        call("recordLiveData", caller, (values == null ? "" : values.toString()) + " " + duration);
        if (duration == null || duration.compareTo(MIN_RECORDING) < 0 || duration.compareTo(MAX_RECORDING) > 0) {
            throw new InvalidLiveValueException("recording duration must be 1 to 120 seconds");
        }
        check(values);
        return SampleScanData.liveDataRecording(values, duration);
    }

    /** The same checks as the real service, against the catalogs listed so far. */
    private void check(List<LiveValueId> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_LIVE_VALUES) {
            throw new InvalidLiveValueException("pass 1 to " + MAX_LIVE_VALUES + " value IDs");
        }
        ModuleAddress m = values.getFirst().module();
        LiveDataCatalog c = listedCatalogs.get(m);
        if (c == null) {
            throw new InvalidLiveValueException("no catalog for module " + m.hex() + " yet: list its available data first");
        }
        for (LiveValueId id : values) {
            if (!id.module().equals(m) || c.field(id).isEmpty()) {
                throw new InvalidLiveValueException("value " + id + " is not in module " + m.hex() + "'s catalog");
            }
        }
        if (values.stream().map(LiveValueId::group).distinct().count() > MAX_LIVE_GROUPS) {
            throw new InvalidLiveValueException("too many groups");
        }
    }

    @Override
    public void recordToolCall(ToolCallRecord record) {
        calls.add(new Call("recordToolCall", "mcp:" + record.tool(), record.tool()));
        toolCalls.add(record);
    }

    @Override
    public void close() {
        closed = true;
    }
}
