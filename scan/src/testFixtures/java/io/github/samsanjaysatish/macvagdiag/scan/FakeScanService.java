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
