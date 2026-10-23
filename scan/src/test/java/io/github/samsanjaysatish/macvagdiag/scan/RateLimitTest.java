package io.github.samsanjaysatish.macvagdiag.scan;

import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.busy;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.kwp;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.UdsDtc;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pacing, budgets, rate limits, retries with back-off and not-responding back-off, on the fake clock. */
class RateLimitTest {

    private static List<Long> millisOf(FakeModuleConnector car, String module, String what) {
        return car.events(module).stream().filter(e -> e.what().equals(what))
                .map(e -> Duration.ofNanos(e.atNanos()).toMillis()).toList();
    }

    @Test
    @DisplayName("at least minRequestGap between exchanges with the car, also across calls")
    void minimumGap() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> kwp());
            t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            List<FakeModuleConnector.Event> ev = t.connector().events("17").stream()
                    .filter(e -> !e.what().equals("close")).toList();
            for (int i = 1; i < ev.size(); i++) {
                long gap = ev.get(i).atNanos() - ev.get(i - 1).atNanos();
                assertTrue(gap >= ScanLimits.DEFAULT_MIN_REQUEST_GAP.toNanos(), ev.get(i - 1) + " -> " + ev.get(i));
            }
        }
    }

    @Test
    @DisplayName("requests per minute: the call over the limit sends nothing and says when to retry")
    void requestsPerMinute() throws InterruptedException {
        ScanLimits limits = ScanLimits.builder().maxRequestsPerMinute(10).build();
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), limits)) {
            t.connector().kwp("17", () -> kwp());
            for (int i = 0; i < 5; i++) { // 10 89 + 18 02 each
                assertEquals(ModuleStatus.OK, t.service().readFaultCodes(Caller.mcp("read_fault_codes"),
                        ModuleAddress.INSTRUMENTS).status());
            }
            int before = t.connector().events().size();
            ScanRefusedException e = assertThrows(ScanRefusedException.class,
                    () -> t.service().readFaultCodes(Caller.mcp("read_fault_codes"), ModuleAddress.INSTRUMENTS));
            assertEquals(ScanRefusedException.Reason.LIMIT_EXCEEDED, e.reason());
            assertEquals(Limit.REQUESTS_PER_MINUTE, e.limit().orElseThrow());
            Duration retry = e.retryAfter().orElseThrow();
            assertTrue(retry.compareTo(Duration.ZERO) > 0 && retry.compareTo(Duration.ofMinutes(1)) <= 0, retry.toString());
            assertTrue(e.getMessage().startsWith("rate limit hit: at most 10 requests per minute to the car; retry after"),
                    e.getMessage());
            assertEquals(before, t.connector().events().size(), "nothing sent");
            assertTrue(t.auditLines("refused").stream().anyMatch(l -> l.contains("\"limit\":\"REQUESTS_PER_MINUTE\"")
                    && l.contains("\"caller\":\"mcp:read_fault_codes\"")));

            t.time().advance(retry);
            assertEquals(ModuleStatus.OK, t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS).status());
        }
    }

    @Test
    @DisplayName("budget per module per scan: freeze frames beyond it are skipped, naming the limit")
    void perModuleBudget() throws InterruptedException {
        ScanLimits limits = ScanLimits.builder().maxRequestsPerModulePerScan(5).build();
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), limits)) {
            UdsDtc a = new UdsDtc(0x040100, 0x2F);
            UdsDtc b = new UdsDtc(0x246300, 0x08);
            UdsDtc c = new UdsDtc(0x010000, 0x08);
            t.connector().uds("01", () -> Scripts.uds().onReadDtcs(ok(FakeDiagnosticService.dtcList(a, b, c)))
                    .onReadFreezeFrame(ok(FakeDiagnosticService.freezeFrame(a))));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), ModuleAddress.ENGINE);
            assertEquals(ModuleStatus.FAULTS, r.status());
            assertEquals(3, r.faults().size(), "the DTC list itself is complete");
            assertEquals(FreezeFrameData.Availability.READ, r.faults().get(0).freezeFrame().availability());
            assertEquals(FreezeFrameData.Availability.SKIPPED, r.faults().get(1).freezeFrame().availability());
            assertEquals(FreezeFrameData.Availability.SKIPPED, r.faults().get(2).freezeFrame().availability());
            assertEquals(Limit.REQUESTS_PER_MODULE_PER_SCAN, r.limitHit().orElseThrow().limit());
            assertTrue(r.faults().get(1).freezeFrame().message().contains("at most 5 requests to module 01 in one scan"));
            assertEquals(1, t.connector().count("01", "READ_FREEZE_FRAME"), "nothing sent beyond the budget");
            assertEquals(4, r.requests());
        }
    }

    @Test
    @DisplayName("7F 18 21 busy is retried with exponential back-off: 500 ms, then 1000 ms")
    void busyBackoff() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadDtcs(busy(0x18)).onReadDtcs(busy(0x18)).onReadDtcs(ok(FakeDiagnosticService.dtcList())));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            assertEquals(ModuleStatus.OK, r.status());
            List<Long> at = millisOf(t.connector(), "17", "READ_DTCS");
            assertEquals(3, at.size());
            assertEquals(500, at.get(1) - at.get(0));
            // 1000 ms back-off; the TP 2.0 keep-alive that came due at its end goes first, then the 100 ms gap.
            assertEquals(1100, at.get(2) - at.get(1));
            assertEquals(List.of(1700L), millisOf(t.connector(), "17", "keep-alive"));
            assertTrue(t.auditLines("result").stream().anyMatch(l -> l.contains("\"outcome\":\"busy\"")
                    && l.contains("\"nrc\":\"21\"") && l.contains("\"attempt\":1")));
        }
    }

    @Test
    @DisplayName("busy three times: gives up after 2 retries, the module stays reported as answering")
    void busyGivesUp() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadDtcs(busy(0x18)).onReadDtcs(busy(0x18)).onReadDtcs(busy(0x18)));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            assertEquals(ModuleStatus.ERROR, r.status());
            assertTrue(r.notes().getFirst().contains("Module busy"), r.notes().toString());
            assertEquals(3, t.connector().count("17", "READ_DTCS"));
        }
    }

    @Test
    @DisplayName("a module that didn't respond isn't asked again for notRespondingBackoff")
    void notRespondingBackoff() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            ModuleAddress steering = ModuleAddress.parse("16");
            assertEquals(ModuleStatus.NOT_RESPONDING, t.service().readFaultCodes(Caller.web(), steering).status());
            assertEquals(2, t.connector().count("16", "connect-failed"));
            ScanRefusedException e = assertThrows(ScanRefusedException.class,
                    () -> t.service().readFaultCodes(Caller.mcp("read_fault_codes"), steering));
            assertEquals(ScanRefusedException.Reason.MODULE_NOT_RESPONDING, e.reason());
            assertEquals(Limit.MODULE_NOT_RESPONDING_BACKOFF, e.limit().orElseThrow());
            assertEquals(steering, e.module().orElseThrow());
            Duration retry = e.retryAfter().orElseThrow();
            assertTrue(retry.compareTo(ScanLimits.DEFAULT_NOT_RESPONDING_BACKOFF) <= 0 && !retry.isZero());
            assertTrue(e.getMessage().startsWith("module 16 (Steering Wheel) not responding"), e.getMessage());
            assertEquals(2, t.connector().count("16", "connect-failed"), "nothing sent");
            assertTrue(t.auditLines("refused").stream().anyMatch(l -> l.contains("\"reason\":\"backoff\"")));

            t.time().advance(retry);
            t.service().readFaultCodes(Caller.web(), steering);
            assertEquals(4, t.connector().count("16", "connect-failed"), "asked again after the back-off");
        }
    }

    @Test
    @DisplayName("an Auto-Scan that hits the per-minute limit stops and marks the rest skipped with the limit")
    void autoScanStopsAtRateLimit() throws InterruptedException {
        ScanLimits limits = ScanLimits.builder().maxRequestsPerMinute(10).build();
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), limits)) {
            for (ModuleAddress m : ModuleAddress.known()) {
                t.connector().kwp(m.hex(), () -> Scripts.kwpScan("id")); // 10 89, 1A 9B + 1A 90, 18 02 = 4 each
            }
            AutoScanResult r = t.service().autoScan(Caller.web(), null);
            assertEquals(ModuleStatus.OK, r.modules().get(0).status());
            assertEquals(ModuleStatus.OK, r.modules().get(1).status());
            ModuleResult third = r.modules().get(2);
            assertEquals(ModuleStatus.INCOMPLETE, third.status());
            assertEquals(Limit.REQUESTS_PER_MINUTE, third.limitHit().orElseThrow().limit());
            for (ModuleResult rest : r.modules().subList(3, r.modules().size())) {
                assertEquals(ModuleStatus.SKIPPED, rest.status(), rest.module().hex());
                assertEquals(Limit.REQUESTS_PER_MINUTE, rest.limitHit().orElseThrow().limit());
                assertEquals(List.of(), t.connector().events(rest.module().hex()));
            }
            assertTrue(r.notes().getFirst().startsWith("Auto-Scan stopped: rate limit hit"), r.notes().toString());
            assertTrue(t.service() instanceof DefaultScanService d && d.engine().budget().usedInWindow() <= 10);
        }
    }

    @Test
    @DisplayName("limits have hard bounds: pacing and budgets can't be configured away")
    void hardBounds() {
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().minRequestGap(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().maxRequestsPerMinute(100_000).build());
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().maxRequestsPerModulePerScan(1).build());
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().testerPresentInterval(Duration.ofSeconds(10)).build());
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().maxRetries(10).build());
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().maxBackoff(Duration.ofMillis(100)).build());
        assertThrows(IllegalArgumentException.class, () -> ScanLimits.builder().notRespondingBackoff(Duration.ZERO).build());
        ScanLimits d = ScanLimits.DEFAULTS;
        assertEquals(List.of(500L, 1000L, 2000L, 4000L, 4000L),
                List.of(1, 2, 3, 4, 5).stream().map(i -> d.backoff(i).toMillis()).toList());
        assertEquals(d, d.toBuilder().build());
    }

    @Test
    @DisplayName("an operation's reservation can't be exceeded: an unreserved request is stopped by the audit hook")
    void unreservedRequestIsStopped() {
        try (TestScanServices t = TestScanServices.create()) {
            RequestObserver hook = t.connector().observer();
            assertThrows(ScanEngine.UnreservedRequestException.class, () -> hook.sending("TP2.0 module 17", "KWP2000", "18 02"));
            assertTrue(t.auditLines("refused").stream().anyMatch(l -> l.contains("\"reason\":\"unreserved\"")
                    && l.contains("18 02")));
            assertEquals(List.of(), t.auditLines("request"), "never recorded as sent");
        }
    }

    @Test
    @DisplayName("a negative answer is not a failure: the module isn't given up")
    void negativeIsNotFailure() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadDtcs(new DiagResult.Negative<>(io.github.samsanjaysatish.macvagdiag.diag.NegativeResponse.of(0x18, 0x12))));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            assertEquals(ModuleStatus.ERROR, r.status());
            assertTrue(r.notes().getFirst().contains("7F 18 12"), r.notes().toString());
            assertEquals(1, t.connector().count("17", "READ_DTCS"), "a negative answer isn't retried");
            assertEquals(ModuleStatus.ERROR, t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS).status(),
                    "and no back-off for a module that answers");
        }
    }
}
