package io.github.samsanjaysatish.macvagdiag.scan;

import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.kwp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.KwpDtc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One queue, one worker: concurrent callers get their own results, and requests reach the car one at a time. */
class RequestQueueTest {

    private static final Map<String, Integer> CODE_PER_MODULE = Map.of("03", 0x0522, "17", 0x0414, "19", 0x3F62);

    private static ScanLimits roomy() {
        return ScanLimits.builder().maxRequestsPerMinute(ScanLimits.REQUESTS_PER_MINUTE_CEILING).build();
    }

    @Test
    @DisplayName("8 threads x 6 reads on 3 modules: each result is its own module's, nothing overlaps, one worker thread")
    void concurrentCallers() throws Exception {
        try (TestScanServices t = TestScanServices.create(io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource.autoAdvancing(), roomy())) {
            CODE_PER_MODULE.forEach((m, code) -> t.connector().kwp(m,
                    () -> kwp(new KwpDtc(code, 0x23)).onReadFreezeFrame(new DiagResult.NotAvailable<>("n/a"))));
            List<String> modules = List.of("03", "17", "19");
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                int offset = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> problems = new ArrayList<>();
                    for (int i = 0; i < 6; i++) {
                        String m = modules.get((offset + i) % modules.size());
                        ModuleResult r = t.service().readFaultCodes(Caller.mcp("read_fault_codes"), ModuleAddress.parse(m));
                        String expected = String.format("%05d", CODE_PER_MODULE.get(m));
                        if (!r.module().hex().equals(m) || r.faults().size() != 1
                                || !r.faults().getFirst().code().equals(expected)) {
                            problems.add(m + " got " + r);
                        }
                    }
                    return problems;
                }));
            }
            start.countDown();
            for (Future<List<String>> f : futures) {
                assertEquals(List.of(), f.get());
            }
            pool.shutdown();
            assertEquals(1, t.connector().maxConcurrentConnections(), "one channel at a time");
            assertEquals(1, t.connector().maxConcurrentCalls(), "one diagnostic call at a time");
            assertEquals(Set.of(RequestQueue.WORKER_NAME), t.connector().callThreads(), "only the worker talks to the car");
            assertEquals(48, t.connector().services().size(), "one connection per read");
            // Each module's events are whole, non-interleaved sequences: connect, session, DTCs, freeze frame, close.
            List<FakeModuleConnector.Event> all = t.connector().events();
            for (int i = 0; i < all.size(); i += 5) {
                List<String> chunk = all.subList(i, i + 5).stream().map(e -> e.module() + " " + e.what()).toList();
                String m = all.get(i).module();
                assertEquals(List.of(m + " connect", m + " START_SESSION", m + " READ_DTCS", m + " READ_FREEZE_FRAME",
                        m + " close"), chunk);
            }
        }
    }

    @Test
    @DisplayName("a direct read can run between the modules of a running Auto-Scan, never inside one")
    void readDuringAutoScan() throws Exception {
        try (TestScanServices t = TestScanServices.create(io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource.autoAdvancing(), roomy())) {
            CODE_PER_MODULE.forEach((m, code) -> t.connector().kwp(m,
                    () -> Scripts.kwpScan("x", new KwpDtc(code, 0x23))
                            .onReadFreezeFrame(new DiagResult.NotAvailable<>("n/a"))));
            CountDownLatch inScan = new CountDownLatch(1);
            AutoScanJob job = t.service().startAutoScan(Caller.web(), e -> {
                if (e instanceof ScanEvent.ModuleStarted s && s.module().hex().equals("03")) {
                    inScan.countDown();
                }
            });
            inScan.await();
            ModuleResult direct = t.service().readFaultCodes(Caller.mcp("read_fault_codes"), ModuleAddress.parse("17"));
            AutoScanResult r = job.await();
            assertEquals("01044", direct.faults().getFirst().code());
            assertEquals(3, r.faultCount());
            assertEquals(1, t.connector().maxConcurrentConnections());
            assertTrue(t.auditLines("result").stream().anyMatch(l -> l.contains("\"caller\":\"mcp:read_fault_codes\"")));
            assertTrue(t.auditLines("result").stream().anyMatch(l -> l.contains("\"caller\":\"web\"")));
        }
    }
}
