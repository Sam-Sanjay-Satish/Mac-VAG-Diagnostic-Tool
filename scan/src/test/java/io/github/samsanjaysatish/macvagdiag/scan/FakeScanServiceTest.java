package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The front-end fixture behaves like the real service's contract. */
class FakeScanServiceTest {

    @Test
    @DisplayName("defaults to sample data, records calls, completed Auto-Scan jobs send the usual events")
    void sampleData() throws InterruptedException {
        FakeScanService scan = new FakeScanService();
        assertEquals(SessionStatus.AdapterState.CONNECTED, scan.sessionStatus(Caller.web()).adapter());
        assertEquals(13, scan.listModules(Caller.web()).size());
        assertEquals(ModuleStatus.FAULTS, scan.readFaultCodes(Caller.mcp("read_fault_codes"), ModuleAddress.ENGINE).status());
        List<ScanEvent> events = new ArrayList<>();
        AutoScanJob job = scan.startAutoScan(Caller.web(), events::add);
        assertTrue(job.isDone());
        assertEquals(SampleScanData.autoScan(), job.await());
        assertEquals(2 + 2 * 13, events.size());
        assertInstanceOf(ScanEvent.Finished.class, events.getLast());
        assertEquals(13, job.completedModules());
        assertEquals(job, scan.findAutoScan(job.id()).orElseThrow());
        assertEquals("readFaultCodes", scan.calls().get(2).method());
        assertEquals("mcp:read_fault_codes", scan.calls().get(2).caller());
        assertEquals("01", scan.calls().get(2).argument());
    }

    @Test
    @DisplayName("failWith makes every call refuse, like the real service does for a missing adapter or a limit")
    void failures() {
        FakeScanService scan = new FakeScanService().failWith(ScanRefusedException.limitExceeded(
                new LimitHit(Limit.REQUESTS_PER_MINUTE, java.time.Duration.ofSeconds(5), "rate limit hit: retry after 5 s")));
        ScanRefusedException e = assertThrows(ScanRefusedException.class,
                () -> scan.readFaultCodes(Caller.web(), ModuleAddress.ENGINE));
        assertEquals(java.time.Duration.ofSeconds(5), e.retryAfter().orElseThrow());
        scan.failWith(null);
        scan.recordToolCall(new ToolCallRecord("list_modules", Map.of(), "ok", "13 modules"));
        assertEquals("list_modules", scan.toolCalls().getFirst().tool());
        scan.close();
        assertThrows(ScanRefusedException.class, () -> scan.sessionStatus(Caller.web()));
    }

    @Test
    @DisplayName("a failed job reports its failure")
    void failedJob() {
        AutoScanJob job = AutoScanJob.failed("scan-x", Caller.web(), SampleScanData.AT,
                ScanRefusedException.adapterNotConnected("unplugged"));
        assertTrue(job.isDone());
        assertTrue(job.result().isEmpty());
        assertEquals(ScanRefusedException.Reason.ADAPTER_NOT_CONNECTED,
                ((ScanRefusedException) job.failure().orElseThrow()).reason());
        assertThrows(ScanRefusedException.class, job::await);
    }
}
