package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAvailability;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleResult;
import io.github.samsanjaysatish.macvagdiag.scan.ScanProgressListener;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import io.github.samsanjaysatish.macvagdiag.scan.VehicleInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The {@link ScanService} bean of the MVC slice tests: delegates to a fresh {@link FakeScanService} per test
 * ({@link #reset()}), plus extra jobs a test can register for {@link #findAutoScan} (e.g. a failed one).
 */
final class SwappableScanService implements ScanService {

    private volatile FakeScanService fake = new FakeScanService();
    private final Map<String, AutoScanJob> extraJobs = new ConcurrentHashMap<>();

    /** A new fake with {@code SampleScanData} defaults; returns it for scripting. */
    FakeScanService reset() {
        fake = new FakeScanService();
        extraJobs.clear();
        return fake;
    }

    FakeScanService fake() {
        return fake;
    }

    void addJob(AutoScanJob job) {
        extraJobs.put(job.id(), job);
    }

    @Override
    public boolean isDemo() {
        return fake.isDemo();
    }

    @Override
    public SessionStatus sessionStatus(Caller caller) {
        return fake.sessionStatus(caller);
    }

    @Override
    public List<ModuleAvailability> listModules(Caller caller) {
        return fake.listModules(caller);
    }

    @Override
    public ModuleResult readFaultCodes(Caller caller, ModuleAddress module) {
        return fake.readFaultCodes(caller, module);
    }

    @Override
    public VehicleInfo vehicleInfo(Caller caller) {
        return fake.vehicleInfo(caller);
    }

    @Override
    public AutoScanJob startAutoScan(Caller caller, ScanProgressListener listener) {
        return fake.startAutoScan(caller, listener);
    }

    @Override
    public Optional<AutoScanJob> findAutoScan(String scanId) {
        AutoScanJob extra = extraJobs.get(scanId);
        return extra != null ? Optional.of(extra) : fake.findAutoScan(scanId);
    }

    @Override
    public void recordToolCall(ToolCallRecord call) {
        fake.recordToolCall(call);
    }

    @Override
    public void close() {
        fake.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        SwappableScanService scanService() {
            return new SwappableScanService();
        }
    }
}
