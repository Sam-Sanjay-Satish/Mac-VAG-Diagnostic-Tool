package io.github.samsanjaysatish.macvagdiag.scan;

import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.busy;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.ok;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.okHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tester Present and TP 2.0 keep-alive are scheduled by scan on the fake clock during long operations (here: the
 * back-off after two busy answers), and stop when the session closes. Sessions are closed after errors and
 * interruption too.
 */
class KeepAliveTest {

    /** A KWP2000 module over TP 2.0 (09 Central Electrics). */
    private static final ModuleAddress BCM = ModuleAddress.parse("09");

    /**
     * Back-offs of 1.5 s and 3 s, so the idle time crosses both intervals, pinned here to keep-alive 1 s and Tester
     * Present 2 s so the timelines below read easily (the defaults are tested in {@link #defaultsFollowVcds}).
     */
    private static final ScanLimits LONG_BACKOFF = ScanLimits.builder().initialBackoff(Duration.ofMillis(1500))
            .maxBackoff(Duration.ofSeconds(4)).connectAttempts(1).channelKeepAliveInterval(Duration.ofSeconds(1))
            .testerPresentInterval(Duration.ofSeconds(2)).build();

    private static FakeDiagnosticService busyBusyOk(FakeDiagnosticService base, int sid) {
        return base.onReadDtcs(busy(sid)).onReadDtcs(busy(sid)).onReadDtcs(ok(FakeDiagnosticService.dtcList()))
                .onTesterPresent(okHex(sid == 0x18 ? "7E" : "7E 00"));
    }

    /** "ms what" for one module, times relative to its first connect, without failed connects. */
    private static List<String> timeline(FakeModuleConnector car, String module) {
        List<FakeModuleConnector.Event> ev = car.events(module).stream()
                .filter(e -> !e.what().equals("connect-failed")).toList();
        long t0 = ev.getFirst().atNanos();
        return ev.stream().map(e -> Duration.ofNanos(e.atNanos() - t0).toMillis() + " " + e.what()).toList();
    }

    @Test
    @DisplayName("KWP over TP 2.0: A3 keep-alive every 1 s idle during back-off, no KWP 3E (VCDS sends none), nothing after close")
    void kwpKeepAliveAndTesterPresent() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), LONG_BACKOFF)) {
            t.connector().kwp("09", () -> busyBusyOk(FakeDiagnosticService.kwp().onStartSession(okHex("50 89")), 0x18));
            assertEquals(ModuleStatus.OK, t.service().readFaultCodes(Caller.web(), BCM).status());
            assertEquals(List.of(
                    "0 connect",
                    "100 START_SESSION",
                    "200 READ_DTCS",         // busy: back-off 1.5 s
                    "1200 keep-alive",       // 1 s idle
                    "1700 READ_DTCS",        // busy: back-off 3 s
                    "2700 keep-alive",
                    "3700 keep-alive",       // 2 s since the last request: still no 3E on TP 2.0
                    "4700 keep-alive",       // due as the back-off ends; then the 100 ms gap
                    "4800 READ_DTCS",
                    "4800 close"), timeline(t.connector(), "09"));
            int events = t.connector().events().size();
            t.time().advance(Duration.ofSeconds(30));
            assertEquals(events, t.connector().events().size(), "no keep-alive after the session closed");
            assertEquals(0, t.connector().openConnections());
            assertTrue(t.auditLines("keep-alive").size() == 4 && t.auditLines("keep-alive").getFirst().contains("\"alive\":true"));
            assertTrue(t.auditLines("result").stream().noneMatch(l -> l.contains("\"operation\":\"testerPresent\"")));
            assertEquals(0, t.connector().count("09", "TESTER_PRESENT"));
        }
    }

    @Test
    @DisplayName("defaults follow VCDS on the capture: A3 after 500 ms idle on TP 2.0 and nothing else; Tester Present 1.5 s is for UDS")
    void defaultsFollowVcds() throws InterruptedException {
        assertEquals(Duration.ofMillis(500), ScanLimits.DEFAULTS.channelKeepAliveInterval());
        assertEquals(Duration.ofMillis(1500), ScanLimits.DEFAULTS.testerPresentInterval());
        ScanLimits limits = ScanLimits.builder().initialBackoff(Duration.ofMillis(1500)).connectAttempts(1).build();
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), limits)) {
            t.connector().kwp("09", () -> busyBusyOk(FakeDiagnosticService.kwp().onStartSession(okHex("50 89")), 0x18));
            ModuleResult r = t.service().readFaultCodes(Caller.web(), BCM);
            assertEquals(ModuleStatus.OK, r.status(), r.notes() + " " + timeline(t.connector(), "09"));
            assertEquals(List.of(
                    "0 connect",
                    "100 START_SESSION",
                    "200 READ_DTCS",         // busy: back-off 1.5 s
                    "700 keep-alive",        // 500 ms idle
                    "1200 keep-alive",
                    "1700 keep-alive",       // due as the back-off ends; then the 100 ms gap. No 3E: A3 only
                    "1800 READ_DTCS",        // busy: back-off 3 s
                    "2300 keep-alive",
                    "2800 keep-alive",
                    "3300 keep-alive",
                    "3800 keep-alive",
                    "4300 keep-alive",
                    "4800 keep-alive",
                    "4900 READ_DTCS",
                    "4900 close"), timeline(t.connector(), "09"));
        }
    }

    @Test
    @DisplayName("UDS over ISO-TP: Tester Present only (ISO-TP has no transport keep-alive)")
    void udsTesterPresentOnly() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), LONG_BACKOFF)) {
            t.connector().uds("17", () -> busyBusyOk(Scripts.uds(), 0x19));
            t.service().readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS);
            assertEquals(List.of(
                    "0 connect",
                    "100 START_SESSION",
                    "200 READ_DTCS",
                    "1700 READ_DTCS",
                    "3700 TESTER_PRESENT",
                    "4700 READ_DTCS",
                    "4700 close"), timeline(t.connector(), "17"));
        }
    }

    @Test
    @DisplayName("no Tester Present while requests keep the session busy (slow module, no idle time)")
    void noTesterPresentWhenBusy() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().callDuration(Duration.ofMillis(900));
            t.connector().kwp("09", () -> Scripts.kwpScan("x"));
            t.service().autoScan(Caller.web(), null);
            assertEquals(0, t.connector().count("09", "TESTER_PRESENT"));
            assertEquals(0, t.connector().count("09", "keep-alive"));
        }
    }

    @Test
    @DisplayName("a failed TP 2.0 keep-alive ends the session: nothing more is sent, the module is not responding")
    void failedKeepAlive() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create(FakeTimeSource.autoAdvancing(), LONG_BACKOFF)) {
            t.connector().kwp("09", () -> busyBusyOk(FakeDiagnosticService.kwp().onStartSession(okHex("50 89")), 0x18));
            t.connector().keepAliveFails("09");
            ModuleResult r = t.service().readFaultCodes(Caller.web(), BCM);
            assertEquals(ModuleStatus.NOT_RESPONDING, r.status());
            assertTrue(r.notes().stream().anyMatch(n -> n.contains("no answer to the TP 2.0 keep-alive")), r.notes().toString());
            assertEquals(1, t.connector().count("09", "READ_DTCS"));
            assertEquals(0, t.connector().openConnections());
            assertTrue(t.auditLines("keep-alive").getFirst().contains("\"alive\":false"));
        }
    }

    @Test
    @DisplayName("an exception from diag: the module is reported as an error and its channel is closed")
    void errorClosesSession() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("09", () -> FakeDiagnosticService.kwp().onStartSession(okHex("50 89"))); // 18 02 unscripted
            ModuleResult r = t.service().readFaultCodes(Caller.web(), BCM);
            assertEquals(ModuleStatus.ERROR, r.status());
            assertTrue(r.notes().getFirst().startsWith("internal error"), r.notes().toString());
            assertEquals(List.of("connect", "START_SESSION", "READ_DTCS", "close"), t.connector().whats("09"));
            assertEquals(1, t.auditLines("close").size());
        }
    }

    @Test
    @DisplayName("close() while a caller waits mid-operation: the caller gets SERVICE_CLOSED, the channel is closed")
    void interruptionClosesSession() throws Exception {
        FakeTimeSource clock = new FakeTimeSource();
        TestScanServices t = TestScanServices.create(clock, ScanLimits.DEFAULTS);
        t.connector().kwp("09", () -> Scripts.kwp());
        CompletableFuture<ModuleResult> call = CompletableFuture.supplyAsync(() -> {
            try {
                return t.service().readFaultCodes(Caller.web(), BCM);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        clock.awaitWaiters(1); // the worker waits for the pacing gap with the channel open
        assertEquals(1, t.connector().openConnections());
        t.close();
        ExecutionException e = assertThrows(ExecutionException.class, call::get);
        ScanRefusedException refused = (ScanRefusedException) e.getCause();
        assertEquals(ScanRefusedException.Reason.SERVICE_CLOSED, refused.reason());
        assertEquals(0, t.connector().openConnections());
        assertEquals(List.of("connect", "close"), t.connector().whats("09"));
    }
}
