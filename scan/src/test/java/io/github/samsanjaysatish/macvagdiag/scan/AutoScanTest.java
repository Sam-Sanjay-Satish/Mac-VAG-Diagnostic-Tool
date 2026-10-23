package io.github.samsanjaysatish.macvagdiag.scan;

import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.kwp;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.kwpScan;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.ok;
import static io.github.samsanjaysatish.macvagdiag.scan.Scripts.timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.diag.DiagResult;
import io.github.samsanjaysatish.macvagdiag.diag.EcuIdentification;
import io.github.samsanjaysatish.macvagdiag.diag.FakeDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.KwpDtc;
import io.github.samsanjaysatish.macvagdiag.diag.RawRecord;
import io.github.samsanjaysatish.macvagdiag.diag.UdsDtc;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Discovery and Auto-Scan against fake diag: OK, faults, not responding, timing out partway. */
class AutoScanTest {

    static final String VIN = "WVWZZZ1KZDM123456";
    static final UdsDtc P0401 = new UdsDtc(0x040100, 0x2F);
    static final KwpDtc K01314 = new KwpDtc(0x0522, 0x23);

    /** 01 UDS with a fault, 03 KWP with a fault, 17 OK, 19 timing out on 18 02, 15 dying partway, rest absent. */
    static void scriptCar(FakeModuleConnector car) {
        car.uds("01", () -> Scripts.uds()
                .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.UDS,
                        Map.of(EcuIdentification.Field.PART_NUMBER, "03L906018XX", EcuIdentification.Field.VIN, VIN))))
                .onReadDtcs(ok(FakeDiagnosticService.dtcList(P0401)))
                .onReadFreezeFrame(ok(FakeDiagnosticService.freezeFrame(P0401,
                        new RawRecord("19 04 04 01 00 FF", io.github.samsanjaysatish.macvagdiag.diag.RawBytes.ofHex("01 01 F4 0D 2A"))))));
        car.kwp("03", () -> kwpScan("1K0907379AC ESP", K01314)
                .onReadFreezeFrame(new DiagResult.NotAvailable<>("KWP2000 freeze frames need ...; not on the allowlist")));
        car.kwp("17", () -> kwpScan("5C6920870A KOMBI"));
        car.kwp("19", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000, Map.of())))
                .onReadDtcs(timeout()).onReadDtcs(timeout()).onReadDtcs(timeout()));
        car.kwp("15", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                .onReadIdentification(timeout()).onReadIdentification(timeout()).onReadIdentification(timeout())
                .onReadDtcs(timeout()).onReadDtcs(timeout()).onReadDtcs(timeout()));
    }

    @Test
    @DisplayName("Auto-Scan: OK, faults, not responding, timing out partway; every channel closed")
    void autoScanMixedCar() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            scriptCar(t.connector());
            List<ScanEvent> events = new ArrayList<>();
            AutoScanResult r = t.service().autoScan(Caller.web(), events::add);

            ModuleResult engine = r.module(ModuleAddress.ENGINE).orElseThrow();
            assertEquals(ModuleStatus.FAULTS, engine.status());
            assertEquals(new ModuleLink.IsoTp(0x7E0, 0x7E8, Protocol.UDS), engine.link().orElseThrow(),
                    "TP 2.0 01 is silent, so the ISO-TP link is used");
            FaultCode p0401 = engine.faults().getFirst();
            assertEquals("P0401-00", p0401.displayCode());
            assertEquals("04 01 00 2F", p0401.record());
            assertEquals(FreezeFrameData.Availability.READ, p0401.freezeFrame().availability());
            assertEquals("01 01 F4 0D 2A", p0401.freezeFrame().records().getFirst().hex());
            assertEquals("03L906018XX", engine.identification().orElseThrow().partNumber().orElseThrow());

            ModuleResult abs = r.module(ModuleAddress.parse("03")).orElseThrow();
            assertEquals(ModuleStatus.FAULTS, abs.status());
            assertEquals("01314", abs.faults().getFirst().code());
            assertTrue(abs.faults().getFirst().statusText().contains("symptom 35"));
            assertEquals(FreezeFrameData.Availability.NOT_AVAILABLE, abs.faults().getFirst().freezeFrame().availability());

            assertEquals(ModuleStatus.OK, r.module(ModuleAddress.INSTRUMENTS).orElseThrow().status());

            ModuleResult gateway = r.module(ModuleAddress.GATEWAY).orElseThrow();
            assertEquals(ModuleStatus.ERROR, gateway.status(), "answered, then timed out on the DTC read");
            assertTrue(gateway.notes().stream().anyMatch(n -> n.startsWith("fault codes not read: No response")),
                    gateway.notes().toString());
            assertEquals(3, t.connector().count("19", "READ_DTCS"), "1 try + 2 retries");

            ModuleResult airbag = r.module(ModuleAddress.parse("15")).orElseThrow();
            assertEquals(ModuleStatus.NOT_RESPONDING, airbag.status(), "two failed operations in a row: given up");
            assertTrue(airbag.notes().stream().anyMatch(n -> n.contains("stopped responding")), airbag.notes().toString());

            for (String absent : List.of("02", "08", "09", "16", "25", "44", "46", "56")) {
                ModuleResult m = r.module(ModuleAddress.parse(absent)).orElseThrow();
                assertEquals(ModuleStatus.NOT_RESPONDING, m.status(), absent);
                assertEquals(0, m.requests(), absent);
            }
            assertEquals(2, t.connector().count("16", "connect-failed"), "two channel setups per link");
            assertEquals(4, t.connector().count("02", "connect-failed"), "02 has a TP 2.0 and an ISO-TP link");

            assertEquals(VIN, r.vin().orElseThrow());
            assertEquals(ModuleAddress.ENGINE, r.vinSource().orElseThrow());
            assertEquals(2, r.faultCount());
            assertEquals(4, r.respondingModules().size(), "01 03 17 19 (15 was given up)");
            assertEquals(0, t.connector().openConnections(), "every channel closed");
            assertEquals(1, t.connector().maxConcurrentConnections());
            assertFalse(r.cancelled());

            assertInstanceOf(ScanEvent.Started.class, events.getFirst());
            assertEquals("scanning 01 / Engine... (1 of 13)", events.get(1).message());
            assertInstanceOf(ScanEvent.Finished.class, events.getLast());
            assertEquals(2 + 2 * ModuleAddress.known().size(), events.size());
        }
    }

    @Test
    @DisplayName("listModules probes each known module once, then answers from the cache until the TTL")
    void listModulesProbesAndCaches() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> kwp());
            t.connector().kwp("19", () -> kwp());
            List<ModuleAvailability> list = t.service().listModules(Caller.mcp("list_modules"));
            assertEquals(13, list.size());
            assertEquals(List.of("17", "19"), list.stream()
                    .filter(a -> a.reachability() == ModuleAvailability.Reachability.RESPONDING)
                    .map(a -> a.module().hex()).toList());
            int eventsAfterFirst = t.connector().events().size();
            assertEquals(list, t.service().listModules(Caller.web()), "cached");
            assertEquals(eventsAfterFirst, t.connector().events().size(), "nothing sent for the cached answer");

            t.time().advance(ScanLimits.DEFAULT_MODULE_LIST_CACHE_TTL);
            t.service().listModules(Caller.web());
            assertTrue(t.connector().events().size() > eventsAfterFirst, "probed again after the TTL");
            assertEquals(0, t.connector().openConnections());
        }
    }

    @Test
    @DisplayName("vehicleInfo reads 01, 17, 19 and takes the VIN from the first that reports one")
    void vehicleInfo() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("17", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                    .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000,
                            Map.of(EcuIdentification.Field.VIN, VIN)))));
            VehicleInfo v = t.service().vehicleInfo(Caller.web());
            assertEquals(VIN, v.vin().orElseThrow());
            assertEquals(ModuleAddress.INSTRUMENTS, v.vinSource().orElseThrow());
            assertEquals(List.of("01", "17", "19"), v.modules().stream().map(m -> m.module().hex()).toList());
            assertEquals(ModuleAvailability.Reachability.NOT_RESPONDING, v.modules().getFirst().reachability());
            assertEquals("WVWZZZ1KZDM******", v.maskVin().vin().orElseThrow());
            assertEquals("WVWZZZ1KZDM******", v.maskVin().modules().get(1).identification().orElseThrow().vin().orElseThrow());
        }
    }

    @Test
    @DisplayName("cancel: the module in progress finishes and is closed, the rest are skipped")
    void cancel() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            scriptCar(t.connector());
            AtomicReference<AutoScanJob> job = new AtomicReference<>();
            List<ScanEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            ScanProgressListener listener = e -> {
                events.add(e);
                if (e instanceof ScanEvent.ModuleFinished f && f.result().module().hex().equals("03")) {
                    while (job.get() == null) {
                        Thread.onSpinWait();
                    }
                    job.get().cancel();
                }
            };
            job.set(t.service().startAutoScan(Caller.web(), listener));
            AutoScanResult r = job.get().await();
            assertTrue(r.cancelled());
            assertEquals(ModuleStatus.FAULTS, r.module(ModuleAddress.parse("03")).orElseThrow().status());
            ModuleResult instruments = r.module(ModuleAddress.INSTRUMENTS).orElseThrow();
            assertEquals(ModuleStatus.SKIPPED, instruments.status());
            assertEquals(List.of("Auto-Scan cancelled"), instruments.notes());
            assertEquals(List.of(), t.connector().events("17"), "nothing sent to skipped modules");
            assertEquals(0, t.connector().openConnections());
        }
    }

    @Test
    @DisplayName("only one Auto-Scan at a time; close() interrupts it and closes its channel")
    void oneScanAtATimeAndCloseInterrupts() throws InterruptedException {
        FakeTimeSource clock = new FakeTimeSource();
        TestScanServices t = TestScanServices.create(clock, ScanLimits.DEFAULTS);
        t.connector().kwp("01", () -> kwp()); // engine on TP 2.0 for this test
        AutoScanJob first = t.service().startAutoScan(Caller.web(), null);
        clock.awaitWaiters(1); // the worker is waiting for the pacing gap after 01's session start
        ScanRefusedException e = assertThrows(ScanRefusedException.class,
                () -> t.service().startAutoScan(Caller.mcp("auto_scan"), null));
        assertEquals(ScanRefusedException.Reason.SCAN_IN_PROGRESS, e.reason());
        assertEquals(Limit.ONE_AUTO_SCAN_AT_A_TIME, e.limit().orElseThrow());
        assertTrue(t.auditLines("refused").stream().anyMatch(l -> l.contains("mcp:auto_scan")));

        t.close();
        ScanRefusedException closed = assertThrows(ScanRefusedException.class, first::await);
        assertEquals(ScanRefusedException.Reason.SERVICE_CLOSED, closed.reason());
        assertEquals(0, t.connector().openConnections(), "the interrupted session was closed");
        assertThrows(ScanRefusedException.class, () -> t.service().readFaultCodes(Caller.web(), ModuleAddress.ENGINE));
        assertThrows(ScanRefusedException.class, () -> t.service().startAutoScan(Caller.web(), null));
    }

    @Test
    @DisplayName("adapter not connected: sessionStatus says so, the scan fails and nothing is sent")
    void adapterNotConnected() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            scriptCar(t.connector());
            t.connector().adapterConnected(false, "no CANable found");
            SessionStatus s = t.service().sessionStatus(Caller.web());
            assertEquals(SessionStatus.AdapterState.DISCONNECTED, s.adapter());
            assertEquals("no CANable found", s.adapterDetail());
            assertEquals(SessionStatus.VehicleCondition.State.UNKNOWN, s.ignition().state());
            assertEquals(SessionStatus.VehicleCondition.State.UNKNOWN, s.engine().state());
            assertTrue(s.ignition().reason().contains("allowlist"));

            ScanRefusedException e = assertThrows(ScanRefusedException.class,
                    () -> t.service().autoScan(Caller.web(), null));
            assertEquals(ScanRefusedException.Reason.ADAPTER_NOT_CONNECTED, e.reason());
            assertTrue(e.getMessage().startsWith("adapter not connected: ask the user to plug in the CANable"));
            assertThrows(ScanRefusedException.class, () -> t.service().readFaultCodes(Caller.web(), ModuleAddress.ENGINE));
            assertThrows(ScanRefusedException.class, () -> t.service().listModules(Caller.web()));
            assertEquals(List.of(), t.connector().events(), "nothing sent");

            t.connector().adapterConnected(true, "plugged in");
            assertEquals(SessionStatus.AdapterState.CONNECTED, t.service().sessionStatus(Caller.web()).adapter());
            assertEquals(ModuleStatus.FAULTS, t.service().readFaultCodes(Caller.web(), ModuleAddress.parse("03")).status());
        }
    }

    @Test
    @DisplayName("readFaultCodes reads one module without identification; jobs can be found by ID")
    void readFaultCodesAndFindScan() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("03", () -> kwp(K01314).onReadFreezeFrame(new DiagResult.NotAvailable<>("n/a")));
            ModuleResult r = t.service().readFaultCodes(Caller.mcp("read_fault_codes"), ModuleAddress.parse("03"));
            assertEquals(ModuleStatus.FAULTS, r.status());
            assertTrue(r.identification().isEmpty());
            assertEquals(List.of("connect", "START_SESSION", "READ_DTCS", "READ_FREEZE_FRAME", "close"),
                    t.connector().whats("03"));
            assertEquals(2, r.requests(), "10 89 + 18 02; the KWP freeze frame sends nothing");

            AutoScanJob job = t.service().startAutoScan(Caller.web(), null);
            assertEquals(job.id(), job.await(Duration.ofSeconds(5)).scanId());
            assertEquals(job, t.service().findAutoScan(job.id()).orElseThrow());
            assertTrue(t.service().findAutoScan("nope").isEmpty());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError(e);
        }
    }
}
