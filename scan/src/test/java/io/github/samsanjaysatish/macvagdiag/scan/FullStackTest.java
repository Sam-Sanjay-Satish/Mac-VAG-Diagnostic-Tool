package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole stack: {@link ScanService} → real diag → real transport (guard, TP 2.0, ISO-TP) → {@code SimulatedAdapter}
 * answering as {@link DemoCar}. Proves frames flow, results decode, every request on the wire was audit-logged first,
 * and scan's request reservations match what diag really sends.
 */
class FullStackTest {

    private static final Pattern REQUEST = Pattern.compile("\"module\":\"(\\w\\w)\".*\"request\":\"([0-9A-F ]+)\"");

    /** A diagnostic request decoded from the frames the tester put on the wire. */
    record WireRequest(ApplicationProtocol protocol, String hex) {
    }

    /** The tester's TP 2.0 transmit IDs the demo car negotiates (the target car's). */
    static final java.util.Set<Integer> TP20_TX = java.util.Set.of(0x740, 0x760, 0x790, 0x7A8, 0x32E, 0x338, 0x33C);
    /** The UDS modules' ISO-TP request IDs (the target car's). */
    static final java.util.Set<Integer> ISO_TP_REQUEST = java.util.Set.of(0x714, 0x732, 0x715, 0x711, 0x731, 0x76B);

    /** Reassembles the tester's requests: ISO-TP single frames on the UDS request IDs, TP 2.0 data packets. */
    static List<WireRequest> requestsOnTheWire(List<CanFrame> frames) {
        List<WireRequest> out = new ArrayList<>();
        ByteArrayOutputStream tp = null;
        for (CanFrame f : frames) {
            byte[] d = f.data();
            if (ISO_TP_REQUEST.contains(f.id()) && f.length() > 0 && (f.dataByte(0) >> 4) == 0) {
                out.add(new WireRequest(ApplicationProtocol.UDS,
                        Hex.format(java.util.Arrays.copyOfRange(d, 1, 1 + (f.dataByte(0) & 0x0F)))));
            } else if (TP20_TX.contains(f.id()) && f.length() > 0) {
                int b0 = f.dataByte(0);
                int op = b0 >> 4;
                if (b0 == 0xA0 || b0 == 0xA1 || b0 == 0xA3 || b0 == 0xA8 || op > 3) {
                    continue; // channel control and ACKs
                }
                int from = 1;
                if (tp == null) {
                    tp = new ByteArrayOutputStream();
                    from = 3;
                }
                tp.write(d, from, d.length - from);
                if (op == 1 || op == 3) {
                    out.add(new WireRequest(ApplicationProtocol.KWP2000, Hex.format(tp.toByteArray())));
                    tp = null;
                }
            }
        }
        return out;
    }

    private static List<String[]> auditedRequests(String log) {
        List<String[]> out = new ArrayList<>();
        log.lines().filter(l -> l.contains("\"event\":\"request\"")).forEach(l -> {
            Matcher m = REQUEST.matcher(l);
            assertTrue(m.find(), l);
            out.add(new String[] {m.group(1), m.group(2)});
        });
        return out;
    }

    @Test
    @DisplayName("Auto-Scan of the demo car end to end: modules, codes, statuses, VIN; wire == audit log")
    void autoScanEndToEnd() throws InterruptedException {
        StringWriter log = new StringWriter();
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS, AuditLog.to(log, time, true));
        try (ScanService service = stack.service()) {
            AutoScanResult r = service.autoScan(Caller.web(), null);

            assertTrue(r.fromInstallationList(), r.notes().toString());
            Map<String, ModuleStatus> expected = new java.util.LinkedHashMap<>();
            expected.put("01", ModuleStatus.FAULTS);
            expected.put("02", ModuleStatus.OK);
            expected.put("03", ModuleStatus.FAULTS);
            expected.put("04", ModuleStatus.GATEWAY_STATUS_ONLY);
            expected.put("05", ModuleStatus.OK);
            expected.put("08", ModuleStatus.OK);
            expected.put("09", ModuleStatus.OK);
            expected.put("15", ModuleStatus.OK);
            expected.put("17", ModuleStatus.FAULTS);
            expected.put("19", ModuleStatus.FAULTS);
            expected.put("25", ModuleStatus.OK);
            expected.put("2B", ModuleStatus.OK);
            expected.put("2E", ModuleStatus.NOT_RESPONDING);
            expected.put("44", ModuleStatus.OK);
            expected.put("56", ModuleStatus.NOT_RESPONDING);
            expected.put("77", ModuleStatus.OK);
            Map<String, ModuleStatus> actual = new java.util.LinkedHashMap<>();
            r.modules().forEach(m -> actual.put(m.module().hex(), m.status()));
            assertEquals(expected, actual);
            assertEquals("Cannot be reached 1100", r.module(ModuleAddress.parse("56")).orElseThrow().gatewayStatus()
                    .orElseThrow().vcdsText());
            assertEquals(List.of("01", "02", "03", "04", "05", "08", "09", "15", "16", "17", "19", "25", "2B", "2E", "44",
                    "46", "56", "77").stream().filter(a -> !a.equals("16") && !a.equals("46")).toList(),
                    r.installationList().stream().map(GatewayStatus::hex).toList(), "16 and 46 are listed as not installed");

            ModuleResult engine = r.module(ModuleAddress.ENGINE).orElseThrow();
            assertEquals(new ModuleLink.Tp20(0x01), engine.link().orElseThrow());
            assertEquals(List.of("001025", "001025", "009315"), engine.faults().stream().map(FaultCode::displayCode).toList());
            assertEquals(List.of("P0401", "P0401", "P2463"),
                    engine.faults().stream().map(f -> f.obdCode().orElseThrow()).toList());
            assertEquals("E2 (11100010): type 002, MIL ON", engine.faults().get(0).statusText());
            assertTrue(engine.faults().get(0).freezeFrame().records().getFirst().hex().contains("00 30 39"),
                    "first record of 04 01: 12345 km");
            assertTrue(engine.faults().get(1).freezeFrame().records().getFirst().hex().contains("00 34 21"),
                    "the repeated 12 00 04 04 01 returned the second stored record: 13345 km");
            EcuInfo id = engine.identification().orElseThrow();
            assertEquals("03L906018XX", id.partNumber().orElseThrow());
            assertEquals("DEMO R4 2.0L TDI", id.systemName().orElseThrow());
            assertEquals(DemoCar.VIN, id.vin().orElseThrow());

            ModuleResult instruments = r.module(ModuleAddress.INSTRUMENTS).orElseThrow();
            assertEquals(new ModuleLink.IsoTp(0x714, 0x77E, Protocol.UDS), instruments.link().orElseThrow());
            assertEquals(List.of("B1A10-12", "U0115-00"), instruments.faults().stream().map(FaultCode::displayCode).toList());
            FreezeFrameData ff = instruments.faults().get(0).freezeFrame();
            assertEquals(FreezeFrameData.Availability.READ, ff.availability());
            assertEquals(List.of("19 06 9A 10 12 FF: 01 05 02"),
                    ff.records().stream().map(v -> v.id() + ": " + v.hex()).toList());
            FreezeFrameData refused = instruments.faults().get(1).freezeFrame();
            assertEquals(FreezeFrameData.Availability.FAILED, refused.availability());
            assertEquals(List.of("7F 19 31"), refused.responses(), "U0115's 19 06 refused");
            assertEquals("5C6920870A", instruments.identification().orElseThrow().partNumber().orElseThrow());

            assertEquals("01314", r.module(ModuleAddress.parse("03")).orElseThrow().faults().getFirst().code());
            assertEquals("1K0907379AC", r.module(ModuleAddress.parse("03")).orElseThrow().identification().orElseThrow()
                    .partNumber().orElseThrow(), "1A 9B refused: read with 22 F1 87");
            assertEquals("1K0937049N", r.module(ModuleAddress.parse("09")).orElseThrow().identification().orElseThrow()
                    .partNumber().orElseThrow(), "each 22 answered after a 7F 22 78 with the TP 2.0 length flag");
            FaultCode gw = r.module(ModuleAddress.GATEWAY).orElseThrow().faults().getFirst();
            assertEquals("01044", gw.code());
            assertTrue(gw.statusText().contains("Intermittent"));
            assertEquals("02E300012D", r.module(ModuleAddress.parse("02")).orElseThrow().identification().orElseThrow()
                    .partNumber().orElseThrow());
            assertEquals(DemoCar.VIN, r.vin().orElseThrow());
            assertEquals(ModuleAddress.ENGINE, r.vinSource().orElseThrow());
            assertTrue(r.demo());

            // Every request on the wire was audit-logged first, in the same order, and passes the allowlist.
            List<WireRequest> wire = requestsOnTheWire(stack.adapter().sentFrames());
            List<String[]> audited = auditedRequests(log.toString());
            assertEquals(wire.stream().map(WireRequest::hex).toList(), audited.stream().map(a -> a[1]).toList());
            for (WireRequest w : wire) {
                assertTrue(RequestGuard.isAllowed(w.protocol(), Hex.parse(w.hex())), w.toString());
            }
            assertTrue(wire.stream().anyMatch(w -> w.hex().equals("19 06 9A 10 12 FF")));
            assertEquals(1, count(wire, "22 04 A1"), "the installation list, once");
            assertEquals("22 04 A1", wire.get(1).hex(), "right after 10 89 on the gateway: the first thing read");
            assertEquals(1, count(wire, "18 00 FF 00"), "the engine's DTC read");
            assertEquals(7, count(wire, "18 02 FF 00"), "02 03 08 09 19 44, 08 twice (busy, then answered)");
            assertEquals(2, count(wire, "12 00 04 04 01"), "P0401 is stored twice: one request per list entry");
            assertEquals(1, count(wire, "12 00 04 24 63"));
            assertEquals(1, count(wire, "12 00 04 05 22"), "03's KWP freeze frame");
            assertEquals(1, count(wire, "12 00 04 04 14"), "19's KWP freeze frame");
            assertEquals(6, count(wire, "10 03"), "every UDS module in the extended session, as VCDS does");
            assertEquals(0, count(wire, "10 01"));
            assertEquals(6, count(wire, "19 02 AE"));
            assertTrue(log.toString().contains("\"outcome\":\"busy\""), "08's busy answer was retried");

            // The reservation table (DiagOp costs) covers what diag really sends, and the unused part of each
            // reservation is refunded, so each module's request count is exactly what went on the wire for it.
            for (ModuleResult m : r.modules()) {
                long sent = audited.stream().filter(a -> a[0].equals(m.module().hex())).count();
                assertEquals(m.requests(), sent, m.module().hex());
            }
            assertEquals(wire.size(), r.modules().stream().mapToInt(ModuleResult::requests).sum());

            // Every TP 2.0 channel that was opened was closed with A8.
            long setups = log.toString().lines().filter(l -> l.contains("\"event\":\"connect\"")
                    && l.contains("\"outcome\":\"open\"") && l.contains("TP 2.0")).count();
            long disconnects = stack.adapter().sentFrames().stream()
                    .filter(f -> TP20_TX.contains(f.id()) && f.length() == 1 && f.dataByte(0) == 0xA8).count();
            assertEquals(8, setups, "the gateway for its list, then 01 02 03 08 09 19 44");
            assertEquals(setups, disconnects);
            assertTrue(stack.adapter().sentFrames().stream().noneMatch(f -> f.id() == 0x7DF), "never the OBD broadcast");
            assertTrue(stack.adapter().sentFrames().stream().noneMatch(f -> f.id() == 0x700), "never functional 700");
            assertTrue(stack.adapter().sentFrames().stream().noneMatch(f -> f.id() == 0x7E0), "never 7E0");
        }
    }

    private static long count(List<WireRequest> wire, String hex) {
        return wire.stream().filter(w -> w.hex().equals(hex)).count();
    }

    @Test
    @DisplayName("two threads reading modules at once over the real transport: no channel clash, right answers")
    void concurrentReadsOverRealTransport() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ScanServiceFactory.DemoStack stack = ScanServiceFactory.demoStack(time, ScanLimits.DEFAULTS,
                AuditLog.to(new StringWriter(), time, true));
        try (ScanService service = stack.service()) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<String>> results = new ArrayList<>();
            for (String m : List.of("03", "19", "03", "19", "01", "17")) {
                results.add(pool.submit(() -> {
                    ModuleResult r = service.readFaultCodes(Caller.web(), ModuleAddress.parse(m));
                    return m + ":" + r.status() + ":" + r.faults().stream().map(FaultCode::code).toList();
                }));
            }
            List<String> got = new ArrayList<>();
            for (Future<String> f : results) {
                got.add(f.get());
            }
            pool.shutdown();
            assertEquals(List.of("03:FAULTS:[01314]", "19:FAULTS:[01044]", "03:FAULTS:[01314]", "19:FAULTS:[01044]",
                    "01:FAULTS:[001025, 001025, 009315]", "17:FAULTS:[B1A10, U0115]"), got);
        }
    }

    @Test
    @DisplayName("demo mode through the public factory: demo flag, DEMO banner, audit file lines marked demo")
    void demoModeFactory(@TempDir Path dir) throws Exception {
        Path audit = dir.resolve("audit.jsonl");
        try (ScanService demo = ScanServiceFactory.demo(FakeTimeSource.autoAdvancing(),
                ScanOptions.defaults().withAuditLog(audit))) {
            assertTrue(demo.isDemo());
            SessionStatus s = demo.sessionStatus(Caller.web());
            assertEquals(SessionStatus.AdapterState.CONNECTED, s.adapter());
            assertTrue(s.demo());
            AutoScanResult r = demo.autoScan(Caller.web(), null);
            String report = ScanReport.text(r, true);
            assertTrue(report.contains(ScanReport.DEMO_BANNER));
            assertTrue(report.contains("DEMOVIN0000******"), report);
            assertTrue(!report.contains(DemoCar.VIN));
            assertEquals(List.of("01", "02", "03", "05", "08", "09", "15", "17", "19", "25", "2B", "44", "77"),
                    demo.listModules(Caller.web()).stream()
                    .filter(a -> a.reachability() == ModuleAvailability.Reachability.RESPONDING)
                    .map(a -> a.module().hex()).toList());
        }
        List<String> lines = Files.readAllLines(audit);
        assertTrue(lines.size() > 50);
        assertTrue(lines.stream().allMatch(l -> l.contains("\"demo\":true")));
    }

    @Test
    @DisplayName("a real-adapter service with the adapter failing to open: disconnected, nothing sent")
    void adapterThatWontOpen(@TempDir Path dir) throws InterruptedException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter adapter =
                new io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter(time, new DemoCar());
        adapter.failNextOpen(() -> new io.github.samsanjaysatish.macvagdiag.can.CanAdapterException("port busy"));
        try (ScanService service = ScanServiceFactory.create(adapter, time,
                ScanOptions.defaults().withAuditLog(dir.resolve("a.jsonl")))) {
            SessionStatus s = service.sessionStatus(Caller.web());
            assertEquals(SessionStatus.AdapterState.DISCONNECTED, s.adapter());
            assertTrue(s.adapterDetail().contains("port busy"), s.adapterDetail());
            assertTrue(!s.demo());
            assertEquals(SessionStatus.AdapterState.CONNECTED, service.sessionStatus(Caller.web()).adapter(), "reopened");
            assertEquals(ModuleStatus.OK, service.readFaultCodes(Caller.web(), ModuleAddress.TRANSMISSION).status());
        }
        assertTrue(!adapter.isOpen(), "close() closes the adapter");
    }
}
