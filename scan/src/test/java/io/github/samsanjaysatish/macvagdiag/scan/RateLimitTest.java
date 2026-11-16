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

    /** A KWP2000 module over TP 2.0 (09 Central Electrics). */
    private static final ModuleAddress BCM = ModuleAddress.parse("09");

    private static List<Long> millisOf(FakeModuleConnector car, String module, String what) {
        return car.events(module).stream().filter(e -> e.what().equals(what))
                .map(e -> Duration.ofNanos(e.atNanos()).toMillis()).toList();
    }

    @Test
    @DisplayName("at least minRequestGap between exchanges with the car, also across calls")
    void minimumGap() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("09", () -> kwp());
            t.service().readFaultCodes(Caller.web(), BCM);
            t.service().readFaultCodes(Caller.web(), BCM);
            List<FakeModuleConnector.Event> ev = t.connector().events("09").stream()
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
            t.connector().kwp("09", () -> kwp());
            for (int i = 0; i < 5; i++) { // 10 89 + 18 02 FF 00 each
                assertEquals(ModuleStatus.OK, t.service().readFaultCodes(Caller.mcp("read_fault_codes"),
                        BCM).status());
            }
            int before = t.connector().events().size();
            ScanRefusedException e = assertThrows(ScanRefusedException.class,
                    () -> t.service().readFaultCodes(Caller.mcp("read_fault_codes"), BCM));
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
            assertEquals(ModuleStatus.OK, t.service().readFaultCodes(Caller.web(), BCM).status());
        }
    }

    @Test
    @DisplayName("budget per module per scan: freeze frames beyond it are skipped, naming the limit")
    void perModuleBudget() throws InterruptedException {
        // 10 03 + 19 02 AE + three 19 06 = 5 (the floor); the fourth and fifth 19 06 would exceed it.
        ScanLimits limits = ScanLimits.builder().maxRequestsPerModulePerScan(5).build();
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), limits)) {
            List<UdsDtc> dtcs = List.of(new UdsDtc(0x040100, 0x2F), new UdsDtc(0x246300, 0x08), new UdsDtc(0x010000, 0x08),
                    new UdsDtc(0x020000, 0x08), new UdsDtc(0x030000, 0x08));
            t.connector().uds("17", () -> {
                FakeDiagnosticService s = Scripts.uds().onReadDtcs(ok(FakeDiagnosticService.dtcList(dtcs.toArray(UdsDtc[]::new))));
                for (int i = 0; i < 3; i++) {
                    s.onReadFreezeFrame(ok(FakeDiagnosticService.freezeFrame(dtcs.get(i))));
                }
                return s;
            });
            ModuleResult r = t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            assertEquals(ModuleStatus.FAULTS, r.status());
            assertEquals(5, r.faults().size(), "the DTC list itself is complete");
            for (int i = 0; i < 5; i++) {
                assertEquals(i < 3 ? FreezeFrameData.Availability.READ : FreezeFrameData.Availability.SKIPPED,
                        r.faults().get(i).freezeFrame().availability(), "fault " + i);
            }
            assertEquals(Limit.REQUESTS_PER_MODULE_PER_SCAN, r.limitHit().orElseThrow().limit());
            assertTrue(r.faults().get(3).freezeFrame().message().contains("at most 5 requests to module 17 in one scan"));
            assertEquals(3, t.connector().count("17", "READ_FREEZE_FRAME"), "nothing sent beyond the budget");
            assertEquals(5, r.requests());
        }
    }

    @Test
    @DisplayName("7F 18 21 busy is retried with exponential back-off: 500 ms, then 1000 ms")
    void busyBackoff() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("09", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadDtcs(busy(0x18)).onReadDtcs(busy(0x18)).onReadDtcs(ok(FakeDiagnosticService.dtcList())));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), BCM);
            assertEquals(ModuleStatus.OK, r.status());
            List<Long> at = millisOf(t.connector(), "09", "READ_DTCS");
            assertEquals(3, at.size());
            // 500 ms back-off: the TP 2.0 keep-alive (500 ms idle) comes due at its end and goes first, then the
            // 100 ms gap. 1000 ms back-off: a keep-alive halfway and one at the end, then the gap.
            assertEquals(600, at.get(1) - at.get(0));
            assertEquals(1100, at.get(2) - at.get(1));
            assertEquals(List.of(700L, 1300L, 1800L), millisOf(t.connector(), "09", "keep-alive"));
            assertTrue(t.auditLines("result").stream().anyMatch(l -> l.contains("\"outcome\":\"busy\"")
                    && l.contains("\"nrc\":\"21\"") && l.contains("\"attempt\":1")));
        }
    }

    @Test
    @DisplayName("busy three times: gives up after 2 retries, the module stays reported as answering")
    void busyGivesUp() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("09", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadDtcs(busy(0x18)).onReadDtcs(busy(0x18)).onReadDtcs(busy(0x18)));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), BCM);
            assertEquals(ModuleStatus.ERROR, r.status());
            assertTrue(r.notes().getFirst().contains("Module busy"), r.notes().toString());
            assertEquals(3, t.connector().count("09", "READ_DTCS"));
        }
    }

    @Test
    @DisplayName("a module that didn't respond isn't asked again for notRespondingBackoff")
    void notRespondingBackoff() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            ModuleAddress steering = ModuleAddress.parse("2E"); // TP 2.0 only, silent here
            assertEquals(ModuleStatus.NOT_RESPONDING, t.service().readFaultCodes(Caller.web(), steering).status());
            assertEquals(2, t.connector().count("2E", "connect-failed"));
            ScanRefusedException e = assertThrows(ScanRefusedException.class,
                    () -> t.service().readFaultCodes(Caller.mcp("read_fault_codes"), steering));
            assertEquals(ScanRefusedException.Reason.MODULE_NOT_RESPONDING, e.reason());
            assertEquals(Limit.MODULE_NOT_RESPONDING_BACKOFF, e.limit().orElseThrow());
            assertEquals(steering, e.module().orElseThrow());
            Duration retry = e.retryAfter().orElseThrow();
            assertTrue(retry.compareTo(ScanLimits.DEFAULT_NOT_RESPONDING_BACKOFF) <= 0 && !retry.isZero());
            assertTrue(e.getMessage().startsWith("module 2E (Media Player) not responding"), e.getMessage());
            assertEquals(2, t.connector().count("2E", "connect-failed"), "nothing sent");
            assertTrue(t.auditLines("refused").stream().anyMatch(l -> l.contains("\"reason\":\"backoff\"")));

            t.time().advance(retry);
            t.service().readFaultCodes(Caller.web(), steering);
            assertEquals(4, t.connector().count("2E", "connect-failed"), "asked again after the back-off");
        }
    }

    @Test
    @DisplayName("an Auto-Scan that hits the per-minute limit stops and marks the rest skipped with the limit")
    void autoScanStopsAtRateLimit() throws InterruptedException {
        ScanLimits limits = ScanLimits.builder().maxRequestsPerMinute(20).build();
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), limits)) {
            // The gateway refuses the installation list (10 89 + 22 04 A1 = 2), so every module with a link is probed.
            // Each TP 2.0 module reserves 10 89, 1A 9B + up to 5 more, 18 0x FF 00 = 8: 01 and 02 fit (18), 03 doesn't.
            for (ModuleAddress m : ModuleAddress.known()) {
                if (m.links().stream().anyMatch(l -> l instanceof ModuleLink.Tp20)) {
                    t.connector().kwp(m.hex(), () -> Scripts.kwpScan("id")
                            .onReadInstallationList(Scripts.negative(0x22, 0x31)));
                }
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
                assertEquals(rest.module().equals(ModuleAddress.GATEWAY)
                        ? List.of("connect", "START_SESSION", "READ_INSTALLATION_LIST", "close") : List.of(),
                        t.connector().whats(rest.module().hex()), "only the discovery, nothing after the stop");
            }
            assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("Auto-Scan stopped: rate limit hit")), r.notes().toString());
            assertTrue(t.service() instanceof DefaultScanService d && d.engine().budget().usedInWindow() <= 20);
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
            t.connector().kwp("09", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadDtcs(new DiagResult.Negative<>(io.github.samsanjaysatish.macvagdiag.diag.NegativeResponse.of(0x18, 0x12))));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), BCM);
            assertEquals(ModuleStatus.ERROR, r.status());
            assertTrue(r.notes().getFirst().contains("7F 18 12"), r.notes().toString());
            assertEquals(1, t.connector().count("09", "READ_DTCS"), "a negative answer isn't retried");
            assertEquals(ModuleStatus.ERROR, t.service().readFaultCodes(Caller.web(), BCM).status(),
                    "and no back-off for a module that answers");
        }
    }
}
