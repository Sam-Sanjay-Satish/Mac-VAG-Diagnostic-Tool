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
import io.github.samsanjaysatish.macvagdiag.diag.InstallationList;
import io.github.samsanjaysatish.macvagdiag.diag.KwpDiagnosticService;
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

    static final int ODOMETER_KM = 366280;
    static final UdsDtc B1A10 = new UdsDtc(0x9A1012, 0x09);
    static final KwpDtc K01314 = new KwpDtc(0x0522, 0x23);
    static final KwpDtc P0401 = new KwpDtc(0x0401, 0xE2);
    static final KwpDtc P2463 = new KwpDtc(0x2463, 0x23);

    /**
     * 01 KWP engine with a fault, 03 KWP with a fault, 17 UDS with a fault and the odometer, 19 answering but refusing
     * the installation list and then timing out on {@code 18 02 FF 00}, 15 UDS dying partway, rest absent.
     */
    static void scriptCar(FakeModuleConnector car) {
        car.kwp("01", () -> kwp(P0401)
                .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000,
                        Map.of(EcuIdentification.Field.PART_NUMBER, "03L906018XX"))))
                .onReadFreezeFrame(ok(FakeDiagnosticService.freezeFrame(P0401,
                        new RawRecord("12 00 04 04 01", io.github.samsanjaysatish.macvagdiag.diag.RawBytes.ofHex("6C 01 E2"))))));
        car.kwp("03", () -> kwpScan("1K0907379AC ESP", K01314)
                .onReadFreezeFrame(new DiagResult.NotAvailable<>("no freeze frame stored")));
        car.uds("17", () -> Scripts.uds()
                .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.UDS,
                        Map.of(EcuIdentification.Field.PART_NUMBER, "5C6920870A"))))
                .onReadOdometer(ok(FakeDiagnosticService.odometer(ODOMETER_KM)))
                .onReadDtcs(ok(FakeDiagnosticService.dtcList(B1A10)))
                .onReadFreezeFrame(new DiagResult.NotAvailable<>("none")));
        car.kwp("19", () -> FakeDiagnosticService.kwp().onStartSession(Scripts.okHex("50 89"))
                .onReadInstallationList(Scripts.negative(0x22, 0x31))
                .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.KWP2000, Map.of())))
                .onReadDtcs(timeout()).onReadDtcs(timeout()).onReadDtcs(timeout()));
        car.uds("15", () -> Scripts.uds()
                .onReadIdentification(timeout()).onReadIdentification(timeout()).onReadIdentification(timeout())
                .onReadDtcs(timeout()).onReadDtcs(timeout()).onReadDtcs(timeout()));
    }

    static InstallationList.Entry entry(int address, int tp20, int status) {
        return new InstallationList.Entry(address, tp20, status, 0x10);
    }

    @Test
    @DisplayName("gateway refuses the installation list: every known module with a link is probed (fallback)")
    void autoScanMixedCarFixedList() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            scriptCar(t.connector());
            List<ScanEvent> events = new ArrayList<>();
            AutoScanResult r = t.service().autoScan(Caller.web(), events::add);

            assertFalse(r.fromInstallationList());
            assertTrue(r.notes().getFirst().startsWith("module list: the gateway's installation list was not read "
                    + "(installation list not read: "), r.notes().toString());
            assertEquals(DefaultScanService.Plan.FIXED_LIST, r.modules().stream().map(ModuleResult::module).toList());
            assertTrue(r.module(ModuleAddress.parse("04")).isEmpty(), "04 has no link: not in a fixed-list scan");

            ModuleResult engine = r.module(ModuleAddress.ENGINE).orElseThrow();
            assertEquals(ModuleStatus.FAULTS, engine.status());
            assertEquals(new ModuleLink.Tp20(0x01), engine.link().orElseThrow());
            FaultCode p0401 = engine.faults().getFirst();
            assertEquals("01025", p0401.code(), "diag maps P-codes; the fake DTC carries none");
            assertEquals("04 01 E2", p0401.record());
            assertEquals(FreezeFrameData.Availability.READ, p0401.freezeFrame().availability());
            assertEquals("03L906018XX", engine.identification().orElseThrow().partNumber().orElseThrow());

            ModuleResult abs = r.module(ModuleAddress.parse("03")).orElseThrow();
            assertEquals(ModuleStatus.FAULTS, abs.status());
            assertEquals("01314", abs.faults().getFirst().code());
            assertTrue(abs.faults().getFirst().statusText().contains("type 003, Intermittent"),
                    abs.faults().getFirst().statusText());

            ModuleResult instruments = r.module(ModuleAddress.INSTRUMENTS).orElseThrow();
            assertEquals(ModuleStatus.FAULTS, instruments.status());
            assertEquals(new ModuleLink.IsoTp(0x714, 0x77E, Protocol.UDS), instruments.link().orElseThrow());

            ModuleResult gateway = r.module(ModuleAddress.GATEWAY).orElseThrow();
            assertEquals(ModuleStatus.ERROR, gateway.status(), "answered, then timed out on the DTC read");
            assertTrue(gateway.notes().stream().anyMatch(n -> n.startsWith("fault codes not read: No response")),
                    gateway.notes().toString());
            assertEquals(3, t.connector().count("19", "READ_DTCS"), "1 try + 2 retries");
            assertEquals(1, t.connector().count("19", "READ_INSTALLATION_LIST"));
            assertEquals(ModuleAddress.GATEWAY, t.connector().connectedModules().getFirst(), "the gateway first");

            ModuleResult airbag = r.module(ModuleAddress.parse("15")).orElseThrow();
            assertEquals(ModuleStatus.NOT_RESPONDING, airbag.status(), "two failed operations in a row: given up");
            assertTrue(airbag.notes().stream().anyMatch(n -> n.contains("stopped responding")), airbag.notes().toString());

            for (String absent : List.of("02", "05", "08", "09", "25", "2B", "2E", "44", "56", "77")) {
                ModuleResult m = r.module(ModuleAddress.parse(absent)).orElseThrow();
                assertEquals(ModuleStatus.NOT_RESPONDING, m.status(), absent);
                assertEquals(0, m.requests(), absent);
                assertEquals(2, t.connector().count(absent, "connect-failed"), absent + ": two setups on its one link");
            }

            Mileage mileage = r.mileage().orElseThrow();
            assertEquals(ODOMETER_KM, mileage.kilometres());
            assertEquals(ModuleAddress.INSTRUMENTS, mileage.source());
            assertEquals(r.mileage(), r.module(ModuleAddress.INSTRUMENTS).orElseThrow().mileage());
            assertEquals(1, t.connector().count("17", "READ_ODOMETER"));
            assertEquals(0, t.connector().count("01", "READ_ODOMETER"), "only 17 is asked for the odometer");
            assertEquals(3, r.faultCount());
            assertEquals(4, r.respondingModules().size(), "01 03 17 19 (15 was given up)");
            assertEquals(0, t.connector().openConnections(), "every channel closed");
            assertEquals(1, t.connector().maxConcurrentConnections());
            assertFalse(r.cancelled());

            int total = DefaultScanService.Plan.FIXED_LIST.size();
            assertEquals(15, total);
            assertInstanceOf(ScanEvent.Started.class, events.getFirst());
            assertEquals("Auto-Scan started: 15 modules to check", events.getFirst().message());
            assertEquals("scanning 01 / Engine... (1 of 15)", events.get(1).message());
            assertInstanceOf(ScanEvent.Finished.class, events.getLast());
            assertEquals(2 + 2 * total, events.size());
        }
    }

    @Test
    @DisplayName("installation list: only listed modules are scanned, 04 from the list alone, unknown and unreachable reported")
    void autoScanFromInstallationList() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            FakeModuleConnector car = t.connector();
            car.kwp("19", () -> kwpScan("GATEWAY").onReadInstallationList(ok(FakeDiagnosticService.installationList(
                    entry(0x01, 0x01, 0x05), entry(0x03, 0x03, 0x01), entry(0x04, 0x00, 0x01), entry(0x08, 0x2C, 0x00),
                    entry(0x16, 0x00, 0x01), entry(0x17, 0x07, 0x05), entry(0x19, 0x1F, 0x01),
                    entry(0x2E, 0x54, 0x19)))));
            car.kwp("01", () -> kwpScan("ENGINE", P0401, P0401, P2463)
                    .onReadFreezeFrame(new DiagResult.NotAvailable<>("1st"))
                    .onReadFreezeFrame(new DiagResult.NotAvailable<>("2nd"))
                    .onReadFreezeFrame(new DiagResult.NotAvailable<>("3rd")));
            car.kwp("03", () -> kwpScan("ABS"));
            car.kwp("08", () -> kwpScan("HVAC")); // installed in the car, but the list says not: not contacted
            car.uds("17", () -> Scripts.uds().onReadIdentification(ok(FakeDiagnosticService.identification(
                    ApplicationProtocol.UDS, Map.of()))).onReadOdometer(Scripts.negative(0x22, 0x31))
                    .onReadDtcs(ok(FakeDiagnosticService.dtcList())));

            List<ScanEvent> events = new ArrayList<>();
            AutoScanResult r = t.service().autoScan(Caller.web(), events::add);

            assertTrue(r.fromInstallationList());
            assertEquals(List.of("01", "03", "04", "16", "17", "19", "2E"),
                    r.installationList().stream().map(GatewayStatus::hex).toList(), "every installed entry, list order");
            assertTrue(r.installationList().get(3).module().isEmpty(), "16 isn't in our table");
            assertEquals(List.of("01", "03", "04", "17", "19", "2E"), r.modules().stream().map(m -> m.module().hex()).toList());
            assertEquals("Auto-Scan started: 6 modules to check", events.getFirst().message());
            assertTrue(r.notes().getFirst().startsWith("module list: from the gateway's installation list (7 installed)"),
                    r.notes().toString());
            assertTrue(r.notes().stream().anyMatch(n -> n.contains("module 16 (OK 0000)") && n.contains("not scanned")),
                    r.notes().toString());
            assertEquals(List.of(), car.events("08"), "not listed: never contacted");
            assertEquals(List.of(), car.events("02"));

            ModuleResult steeringAngle = r.module(ModuleAddress.parse("04")).orElseThrow();
            assertEquals(ModuleStatus.GATEWAY_STATUS_ONLY, steeringAngle.status());
            assertEquals("OK 0000", steeringAngle.gatewayStatus().orElseThrow().vcdsText());
            assertEquals(0, steeringAngle.requests());
            assertEquals(List.of(), car.events("04"));

            ModuleResult media = r.module(ModuleAddress.parse("2E")).orElseThrow();
            assertEquals(ModuleStatus.NOT_RESPONDING, media.status());
            assertEquals(GatewayStatus.Kind.CANNOT_BE_REACHED, media.gatewayStatus().orElseThrow().kind());
            assertEquals("Cannot be reached 1100", media.gatewayStatus().orElseThrow().vcdsText());
            assertEquals(2, car.count("2E", "connect-failed"), "two setups, no endless retries");

            ModuleResult engine = r.module(ModuleAddress.ENGINE).orElseThrow();
            assertEquals("Malfunction 0010", engine.gatewayStatus().orElseThrow().vcdsText());
            assertEquals(List.of("1st", "2nd", "3rd"), engine.faults().stream().map(f -> f.freezeFrame().message()).toList(),
                    "one 12 00 04 per DTC-list entry, in list order: a code stored twice is asked twice");
            FakeDiagnosticService engineScan = car.services().stream()
                    .filter(f -> f.calls().stream().anyMatch(c -> c.operation() == FakeDiagnosticService.Operation.READ_FREEZE_FRAME))
                    .findFirst().orElseThrow();
            assertEquals(List.of(P0401, P0401, P2463), engineScan.calls().stream()
                    .filter(c -> c.operation() == FakeDiagnosticService.Operation.READ_FREEZE_FRAME).map(c -> c.argument()).toList());
            assertEquals(1, car.events("01").stream().filter(e -> e.what().equals("connect")).count(), "on one channel");

            ModuleResult gateway = r.module(ModuleAddress.GATEWAY).orElseThrow();
            assertEquals(2 + 1 + KwpDiagnosticService.MAX_IDENTIFICATION_REQUESTS + 1, gateway.requests(),
                    "10 89 + 22 04 A1 for the list, then 10 89 + identification (worst case here) + 18 02 FF 00");
            assertEquals(0, t.connector().openConnections());

            Map<String, ModuleAvailability.Reachability> listing = new java.util.LinkedHashMap<>();
            t.service().listModules(Caller.web()).forEach(a -> listing.put(a.module().hex(), a.reachability()));
            assertEquals(ModuleAvailability.Reachability.NOT_INSTALLED, listing.get("08"), "from the scan's cache");
            assertEquals(ModuleAvailability.Reachability.NOT_CHECKED, listing.get("04"));
            assertEquals(ModuleAvailability.Reachability.RESPONDING, listing.get("17"));
            assertEquals(ModuleAvailability.Reachability.NOT_RESPONDING, listing.get("2E"));
        }
    }

    @Test
    @DisplayName("unused reservations are refunded: requests() counts what was really sent")
    void refundOfUnusedReservations() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("19", () -> kwpScan("GATEWAY").onReadInstallationList(ok(FakeDiagnosticService
                    .installationList(entry(0x19, 0x1F, 0x01)))));
            t.connector().sends("READ_IDENTIFICATION", 2); // 1A 9B answered: 1A 9B + 1A 90, not the 5 fallback DIDs
            AutoScanResult r = t.service().autoScan(Caller.web(), null);
            ModuleResult gateway = r.module(ModuleAddress.GATEWAY).orElseThrow();
            assertEquals(2 + 1 + 2 + 1, gateway.requests(), "list (2) + 10 89 + identification (2 of 6) + 18 02 FF 00");
            assertEquals(gateway.requests(), t.auditLines("request").size(), "requests() == audited sends");
            assertTrue(t.service() instanceof DefaultScanService d && d.engine().budget().usedInWindow() == 6,
                    "the one-minute window holds only what was sent");
        }
    }

    @Test
    @DisplayName("01 Engine gets KwpOptions.ENGINE (18 00 FF 00), every other module DEFAULT (18 02 FF 00)")
    void kwpOptionsPerModule() {
        assertEquals(io.github.samsanjaysatish.macvagdiag.diag.KwpOptions.ENGINE, ChannelConnector.kwpOptions(ModuleAddress.ENGINE));
        for (ModuleAddress m : ModuleAddress.known()) {
            if (!m.equals(ModuleAddress.ENGINE)) {
                assertEquals(io.github.samsanjaysatish.macvagdiag.diag.KwpOptions.DEFAULT, ChannelConnector.kwpOptions(m), m.hex());
            }
        }
    }

    @Test
    @DisplayName("listModules reads the installation list and probes listed modules; others are not installed")
    void listModulesWithInstallationList() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("19", () -> kwp().onReadInstallationList(ok(FakeDiagnosticService.installationList(
                    entry(0x09, 0x20, 0x01), entry(0x19, 0x1F, 0x01), entry(0x04, 0x00, 0x01)))));
            t.connector().kwp("09", () -> kwp());
            t.connector().kwp("08", () -> kwp());
            List<ModuleAvailability> list = t.service().listModules(Caller.web());
            assertEquals(ModuleAddress.known().size(), list.size());
            Map<String, ModuleAvailability.Reachability> by = new java.util.LinkedHashMap<>();
            list.forEach(a -> by.put(a.module().hex(), a.reachability()));
            assertEquals(ModuleAvailability.Reachability.RESPONDING, by.get("09"));
            assertEquals(ModuleAvailability.Reachability.RESPONDING, by.get("19"));
            assertEquals(ModuleAvailability.Reachability.NOT_CHECKED, by.get("04"));
            assertEquals(ModuleAvailability.Reachability.NOT_INSTALLED, by.get("08"));
            assertEquals(List.of(), t.connector().events("08"), "not listed: not contacted");
            assertEquals("OK 0000", list.stream().filter(a -> a.module().hex().equals("09")).findFirst().orElseThrow()
                    .gatewayStatus().orElseThrow().vcdsText());
        }
    }

    @Test
    @DisplayName("listModules probes each known module once, then answers from the cache until the TTL")
    void listModulesProbesAndCaches() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().kwp("09", () -> kwp());
            t.connector().kwp("19", () -> kwp().onReadInstallationList(Scripts.negative(0x22, 0x31)));
            List<ModuleAvailability> list = t.service().listModules(Caller.mcp("list_modules"));
            assertEquals(16, list.size());
            assertEquals(List.of("09", "19"), list.stream()
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
    @DisplayName("vehicleInfo identifies 01, 17, 19 and reads the mileage from 17 on the same channel")
    void vehicleInfo() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().uds("17", () -> Scripts.uds()
                    .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.UDS,
                            Map.of(EcuIdentification.Field.PART_NUMBER, "5C6920870A"))))
                    .onReadOdometer(ok(FakeDiagnosticService.odometer(ODOMETER_KM))));
            VehicleInfo v = t.service().vehicleInfo(Caller.web());
            assertEquals(ODOMETER_KM, v.mileage().orElseThrow().kilometres());
            assertEquals(ModuleAddress.INSTRUMENTS, v.mileage().orElseThrow().source());
            assertEquals(List.of("01", "17", "19"), v.modules().stream().map(m -> m.module().hex()).toList());
            assertEquals(ModuleAvailability.Reachability.NOT_RESPONDING, v.modules().getFirst().reachability());
            assertEquals(v.mileage(), v.modules().get(1).mileage());
            assertEquals(1, t.connector().count("17", "connect"), "identification and odometer on one channel");
        }
    }

    @Test
    @DisplayName("17 refusing the odometer (7F 22 31): noted, the scan of 17 goes on, no mileage")
    void odometerRefused() throws InterruptedException {
        try (TestScanServices t = TestScanServices.create()) {
            t.connector().uds("17", () -> Scripts.uds()
                    .onReadIdentification(ok(FakeDiagnosticService.identification(ApplicationProtocol.UDS, Map.of())))
                    .onReadOdometer(Scripts.negative(0x22, 0x31)));
            VehicleInfo v = t.service().vehicleInfo(Caller.web());
            assertTrue(v.mileage().isEmpty());
            assertTrue(v.modules().get(1).notes().stream().anyMatch(n -> n.startsWith("mileage not read: Negative response 7F 22 31")),
                    v.modules().get(1).notes().toString());
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
        t.connector().kwp("01", () -> kwp());
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
            assertEquals(3, r.requests(), "10 89 + 18 02 FF 00 + one 12 00 04 freeze frame");

            AutoScanJob job = t.service().startAutoScan(Caller.web(), null);
            assertEquals(job.id(), job.await(Duration.ofSeconds(5)).scanId());
            assertEquals(job, t.service().findAutoScan(job.id()).orElseThrow());
            assertTrue(t.service().findAutoScan("nope").isEmpty());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError(e);
        }
    }
}
