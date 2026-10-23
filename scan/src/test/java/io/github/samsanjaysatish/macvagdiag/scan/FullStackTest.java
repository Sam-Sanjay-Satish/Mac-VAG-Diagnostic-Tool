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

    /** Reassembles the tester's requests: ISO-TP single frames on 7E0, TP 2.0 data packets on 740..7BF. */
    static List<WireRequest> requestsOnTheWire(List<CanFrame> frames) {
        List<WireRequest> out = new ArrayList<>();
        ByteArrayOutputStream tp = null;
        for (CanFrame f : frames) {
            byte[] d = f.data();
            if (f.id() == 0x7E0 && f.length() > 0 && (f.dataByte(0) >> 4) == 0) {
                out.add(new WireRequest(ApplicationProtocol.UDS,
                        Hex.format(java.util.Arrays.copyOfRange(d, 1, 1 + (f.dataByte(0) & 0x0F)))));
            } else if (f.id() >= 0x740 && f.id() <= 0x7BF && f.length() > 0) {
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

            Map<String, ModuleStatus> expected = new java.util.LinkedHashMap<>();
            expected.put("01", ModuleStatus.FAULTS);
            expected.put("02", ModuleStatus.OK);
            expected.put("03", ModuleStatus.FAULTS);
            expected.put("08", ModuleStatus.OK);
            expected.put("09", ModuleStatus.OK);
            expected.put("15", ModuleStatus.OK);
            expected.put("16", ModuleStatus.NOT_RESPONDING);
            expected.put("17", ModuleStatus.OK);
            expected.put("19", ModuleStatus.FAULTS);
            expected.put("25", ModuleStatus.NOT_RESPONDING);
            expected.put("44", ModuleStatus.NOT_RESPONDING);
            expected.put("46", ModuleStatus.NOT_RESPONDING);
            expected.put("56", ModuleStatus.NOT_RESPONDING);
            Map<String, ModuleStatus> actual = new java.util.LinkedHashMap<>();
            r.modules().forEach(m -> actual.put(m.module().hex(), m.status()));
            assertEquals(expected, actual);

            ModuleResult engine = r.module(ModuleAddress.ENGINE).orElseThrow();
            assertEquals(new ModuleLink.IsoTp(0x7E0, 0x7E8, Protocol.UDS), engine.link().orElseThrow());
            assertEquals(List.of("P0401-00", "P2463-00"), engine.faults().stream().map(FaultCode::displayCode).toList());
            assertEquals("2F: testFailed, testFailedThisOperationCycle, pendingDTC, confirmedDTC, testFailedSinceLastClear",
                    engine.faults().get(0).statusText());
            FreezeFrameData ff = engine.faults().get(0).freezeFrame();
            assertEquals(FreezeFrameData.Availability.READ, ff.availability());
            assertEquals(List.of("19 04 04 01 00 FF: 01 01 F4 0D 2A", "19 06 04 01 00 FF: 01 05 02"),
                    ff.records().stream().map(v -> v.id() + ": " + v.hex()).toList());
            assertTrue(engine.faults().get(1).freezeFrame().notes().stream().anyMatch(n -> n.contains("7F 19 31")));
            EcuInfo id = engine.identification().orElseThrow();
            assertEquals("03L906018XX", id.partNumber().orElseThrow());
            assertEquals("DEMO R4 2.0L TDI", id.systemName().orElseThrow());

            assertEquals("01314", r.module(ModuleAddress.parse("03")).orElseThrow().faults().getFirst().code());
            FaultCode gw = r.module(ModuleAddress.GATEWAY).orElseThrow().faults().getFirst();
            assertEquals("01044", gw.code());
            assertTrue(gw.statusText().contains("intermittent"));
            assertTrue(r.module(ModuleAddress.parse("02")).orElseThrow().identification().orElseThrow().raw().getFirst()
                    .text().orElseThrow().contains("DSG DEMO"));
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
            assertTrue(wire.stream().anyMatch(w -> w.hex().equals("19 06 24 63 00 FF")));
            assertEquals(8, wire.stream().filter(w -> w.hex().equals("18 02")).count(),
                    "seven KWP modules, 08 twice (busy, then answered)");
            assertTrue(log.toString().contains("\"outcome\":\"busy\""), "08's busy answer was retried");

            // The reservation table (DiagOp costs) matches what diag really sends, module by module.
            for (ModuleResult m : r.modules()) {
                long sent = audited.stream().filter(a -> a[0].equals(m.module().hex())).count();
                assertEquals(m.requests(), sent, m.module().hex());
            }

            // Every TP 2.0 channel that was opened was closed with A8.
            long setups = log.toString().lines().filter(l -> l.contains("\"event\":\"connect\"")
                    && l.contains("\"outcome\":\"open\"") && l.contains("TP 2.0")).count();
            long disconnects = stack.adapter().sentFrames().stream()
                    .filter(f -> f.id() >= 0x740 && f.id() <= 0x7BF && f.length() == 1 && f.dataByte(0) == 0xA8).count();
            assertEquals(7, setups);
            assertEquals(setups, disconnects);
            assertTrue(stack.adapter().sentFrames().stream().noneMatch(f -> f.id() == 0x7DF), "never the OBD broadcast");
        }
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
                    "01:FAULTS:[P0401, P2463]", "17:OK:[]"), got);
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
            assertEquals(List.of("01", "02", "03", "08", "09", "15", "17", "19"), demo.listModules(Caller.web()).stream()
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
            assertEquals(ModuleStatus.OK, service.readFaultCodes(Caller.web(), ModuleAddress.INSTRUMENTS).status());
        }
        assertTrue(!adapter.isOpen(), "close() closes the adapter");
    }
}
