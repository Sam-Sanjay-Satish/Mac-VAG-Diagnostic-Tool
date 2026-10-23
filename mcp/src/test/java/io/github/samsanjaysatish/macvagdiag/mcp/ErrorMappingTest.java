package io.github.samsanjaysatish.macvagdiag.mcp;

import static io.github.samsanjaysatish.macvagdiag.mcp.ToolTesting.error;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import io.github.samsanjaysatish.macvagdiag.scan.Limit;
import io.github.samsanjaysatish.macvagdiag.scan.LimitHit;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAvailability;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleResult;
import io.github.samsanjaysatish.macvagdiag.scan.ScanProgressListener;
import io.github.samsanjaysatish.macvagdiag.scan.ScanRefusedException;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.github.samsanjaysatish.macvagdiag.scan.SessionStatus;
import io.github.samsanjaysatish.macvagdiag.scan.ToolCallRecord;
import io.github.samsanjaysatish.macvagdiag.scan.VehicleInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** scan's refusals become messages that say what happened, that nothing was sent, and what to do next. */
class ErrorMappingTest {

    private final FakeScanService scan = new FakeScanService();
    private final McpTools tools = new McpTools(scan, McpOptions.defaults());

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"get_session_status", "list_modules", "read_fault_codes", "get_vehicle_info"})
    void adapterMissing(String tool) {
        scan.failWith(ScanRefusedException.adapterNotConnected("no serial port /dev/cu.usbmodem1"));
        String message = error(call(tool));
        assertTrue(message.startsWith("refused, nothing was sent to the car: adapter not connected: ask the user to "
                + "plug in the CANable"), message);
        assertTrue(message.contains("call get_session_status to check"), message);
        ToolCallRecord audit = scan.toolCalls().getFirst();
        assertEquals(tool, audit.tool());
        assertEquals("refused", audit.outcome());
        assertTrue(audit.summary().startsWith("ADAPTER_NOT_CONNECTED: "), audit.summary());
    }

    @Test
    void rateLimitSaysWhenToRetry() {
        scan.failWith(ScanRefusedException.limitExceeded(new LimitHit(Limit.REQUESTS_PER_MINUTE,
                Duration.ofMillis(11_200), "rate limit hit: at most 400 requests per minute to the car; retry after 12 s")));
        String message = error(call("read_fault_codes"));
        assertTrue(message.contains("rate limit hit: at most 400 requests per minute to the car; retry after 12 s"),
                message);
        assertTrue(message.contains("Wait at least 12 s"), message);
        assertTrue(message.contains("Don't retry in a loop"), message);
    }

    @Test
    void moduleNotRespondingPointsAtListModules() {
        scan.failWith(ScanRefusedException.moduleNotResponding(ModuleAddress.parse("16"), Duration.ofSeconds(5),
                "no answer to channel setup"));
        String message = error(tools.call("read_fault_codes", Map.of("module", "16")));
        assertTrue(message.contains("module 16 (Steering Wheel) not responding"), message);
        assertTrue(message.contains("Call list_modules"), message);
        assertTrue(message.contains("only after 5 s"), message);
    }

    @Test
    void scanInProgressSaysToWait() {
        scan.failWith(ScanRefusedException.scanInProgress("scan-7"));
        String message = error(call("list_modules"));
        assertTrue(message.contains("an Auto-Scan is already running (scan-7)"), message);
        assertTrue(message.contains("wait for it to finish, then retry"), message);
    }

    @Test
    void serviceClosedSaysToRestart() {
        scan.close();
        String message = error(call("get_vehicle_info"));
        assertTrue(message.contains("the scan service is shut down"), message);
        assertTrue(message.contains("Ask the user to restart it"), message);
    }

    @Test
    void unexpectedFailureIsAnErrorResultNotACrash() {
        scan.failWith(new IllegalStateException("boom"));
        String message = error(call("list_modules"));
        assertTrue(message.startsWith("internal error in macvagdiag (IllegalStateException)"), message);
        assertEquals("error", scan.toolCalls().getFirst().outcome());
    }

    @Test
    void auditFailureDoesNotHideTheResult() {
        McpTools t = new McpTools(new AuditFailingScan(scan), McpOptions.defaults());
        ToolTesting.ok(t, "get_session_status", t.call("get_session_status", Map.of()));
        assertEquals(0, scan.toolCalls().size());
    }

    /** Delegates to a fake, but can't write the audit log. */
    private record AuditFailingScan(ScanService delegate) implements ScanService {
        @Override
        public boolean isDemo() {
            return delegate.isDemo();
        }

        @Override
        public SessionStatus sessionStatus(Caller caller) throws InterruptedException {
            return delegate.sessionStatus(caller);
        }

        @Override
        public List<ModuleAvailability> listModules(Caller caller) throws InterruptedException {
            return delegate.listModules(caller);
        }

        @Override
        public ModuleResult readFaultCodes(Caller caller, ModuleAddress module) throws InterruptedException {
            return delegate.readFaultCodes(caller, module);
        }

        @Override
        public VehicleInfo vehicleInfo(Caller caller) throws InterruptedException {
            return delegate.vehicleInfo(caller);
        }

        @Override
        public AutoScanJob startAutoScan(Caller caller, ScanProgressListener listener) {
            return delegate.startAutoScan(caller, listener);
        }

        @Override
        public Optional<AutoScanJob> findAutoScan(String scanId) {
            return delegate.findAutoScan(scanId);
        }

        @Override
        public void recordToolCall(ToolCallRecord call) {
            throw new UncheckedIOException(new IOException("disk full"));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private io.modelcontextprotocol.spec.McpSchema.CallToolResult call(String tool) {
        return tools.call(tool, tool.equals("read_fault_codes") ? Map.of("module", "01") : Map.of());
    }
}
