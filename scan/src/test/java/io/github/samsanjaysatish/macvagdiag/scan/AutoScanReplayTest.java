package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.RealCaptures;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.VcdsLog;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureEcu;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureExchanges;
import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The v1 acceptance test.</b> {@link ScanService#autoScan} runs end to end (scan → diag → transport → adapter)
 * against the real car as recorded on 2026-11-13: a {@link SimulatedAdapter} whose ECU ({@link CaptureEcu}) answers
 * our requests with what the modules answered VCDS in the Auto-Scan capture. The result is compared with the VCDS
 * Auto-Scan log of the same scan (both VIN-masked fixtures).
 *
 * <p>Compared: the installed modules and their status (VCDS's table, including 04 from the gateway list and 2E / 56
 * "Cannot be reached"); every fault code (VCDS number, P-code / SAE code, KWP fault type, status bits and VCDS's
 * status words); the engine's and gateway's KWP freeze frames field for field (except the date); UDS freeze frames
 * read; part numbers; the mileage in VCDS's header (17's {@code 22 22 03}). Also: every request we sent passed the
 * allowlist and was one VCDS sent too, answered from the capture (since 2026-11-17 we no longer read the VIN, the
 * only requests VCDS never sent).
 *
 * <p>Known differences, not compared: fault and failure-type texts (VCDS's database; {@code 00 00 16} is "U1066" for
 * VCDS, {@code P0000} by J2012), freeze-frame dates (KWP: undecodable on this car; UDS: VCDS printed the laptop's
 * wrong date), the VIN in VCDS's header (VCDS reads it with OBD-II {@code 09 02}; we never read the VIN), VCDS's
 * extra identification (serials, ASAM data, subsystems, VCID).
 */
class AutoScanReplayTest {

    private static final VcdsLog VCDS = VcdsLog.parse(RealCaptures.autoScanVcdsLog());

    private static AutoScanResult result;
    private static CaptureEcu ecu;
    private static String audit;

    @BeforeAll
    static void scan() throws InterruptedException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ecu = CaptureEcu.of(RealCaptures.autoScan());
        SimulatedAdapter adapter = new SimulatedAdapter(time, ecu);
        StringWriter log = new StringWriter();
        try (ScanService service = ScanServiceFactory.stack(adapter, time, ScanLimits.DEFAULTS,
                AuditLog.to(log, time, false)).service()) {
            result = service.autoScan(Caller.web(), null);
        }
        audit = log.toString();
    }

    @Test
    @DisplayName("modules and statuses = VCDS's table: 16 installed, from the gateway list; 04 from the list only; 2E, 56 unreachable")
    void modulesAndStatuses() {
        assertTrue(result.fromInstallationList(), result.notes().toString());
        assertTrue(!result.cancelled());
        assertTrue(!result.demo());
        Map<String, String> vcds = new LinkedHashMap<>();
        VCDS.statusTable().forEach(s -> vcds.put(s.address(), s.text()));
        Map<String, String> ours = new LinkedHashMap<>();
        result.installationList().forEach(g -> ours.put(g.hex(), g.vcdsText()));
        assertEquals(vcds, ours, "the gateway's status column, as VCDS prints it");

        Map<String, ModuleStatus> expected = new LinkedHashMap<>();
        VCDS.statusTable().forEach(s -> expected.put(s.address(), switch (s.status()) {
            case "OK" -> s.address().equals("04") ? ModuleStatus.GATEWAY_STATUS_ONLY : ModuleStatus.OK;
            case "Malfunction" -> ModuleStatus.FAULTS;
            case "Cannot be reached" -> ModuleStatus.NOT_RESPONDING;
            default -> throw new AssertionError(s);
        }));
        Map<String, ModuleStatus> actual = new LinkedHashMap<>();
        result.modules().forEach(m -> actual.put(m.module().hex(), m.status()));
        assertEquals(expected, actual);
        for (ModuleResult m : result.modules()) {
            assertEquals(VCDS.status(m.module().hex()).orElseThrow().text(), m.gatewayStatus().orElseThrow().vcdsText(),
                    m.module().hex());
        }
        assertEquals(19, result.faultCount());
        assertEquals(VCDS.faultCount(), result.faultCount());
    }

    @Test
    @DisplayName("every fault code = VCDS's: number, P/SAE code, fault type, status bits and words, in VCDS's order")
    void faultCodes() {
        int compared = 0;
        for (VcdsLog.Module vm : VCDS.modules()) {
            ModuleResult m = result.module(ModuleAddress.parse(vm.address())).orElseThrow();
            assertEquals(vm.faults().size(), m.faults().size(), vm.address());
            assertTrue(m.faultListComplete() || vm.cannotBeReached(), vm.address());
            for (int i = 0; i < vm.faults().size(); i++) {
                VcdsLog.Fault v = vm.faults().get(i);
                FaultCode f = m.faults().get(i);
                String what = vm.address() + " " + v.number();
                String bits = String.format("%8s", Integer.toBinaryString(f.status())).replace(' ', '0');
                if (f.protocol() == Protocol.KWP2000) {
                    assertEquals(v.number(), f.code(), what);
                    assertEquals(v.code(), f.obdCode(), what);
                    String text = f.status() == 0 ? "" : String.format("%02X (%s): type %s", f.status(), bits, v.faultType().orElseThrow());
                    assertEquals(text + (v.flags().isEmpty() ? "" : ", " + String.join(", ", v.flags())), f.statusText(), what);
                    assertEquals(v.freezeFrame("Fault Status").orElseThrow(), bits, what);
                } else {
                    int dtc = Integer.parseInt(f.record().replace(" ", "").substring(0, 6), 16);
                    assertEquals(v.number(), String.format("%04d", dtc), what + ": VCDS number = decimal of the 24-bit DTC");
                    if (!v.number().equals("0022")) {
                        assertEquals(v.code().orElseThrow(), f.code(), what);
                    }
                    assertEquals(v.statusBits().orElseThrow(), bits, what);
                    assertEquals(String.format("%02X (%s): %s", f.status(), bits, String.join(" - ", v.flags())),
                            f.statusText(), what);
                }
                compared++;
            }
        }
        assertEquals(19, compared);
    }

    private static final Map<String, String> VCDS_LABELS = Map.of(
            "STATUS", "Fault Status", "PRIORITY", "Fault Priority", "OCCURRENCE_COUNTER", "Fault Frequency",
            "RESET_COUNTER", "Reset counter", "MILEAGE", "Mileage", "TIME_INDICATION", "Time Indication", "TIME", "Time");

    @Test
    @DisplayName("KWP freeze frames of 01 (12, with 84 environment values) and 19 (2) = VCDS's, field for field except the date")
    void kwpFreezeFrames() {
        int records = 0;
        for (String address : List.of("01", "19")) {
            List<VcdsLog.Fault> vcds = VCDS.module(address).orElseThrow().faults();
            List<FaultCode> ours = result.module(ModuleAddress.parse(address)).orElseThrow().faults();
            for (int i = 0; i < vcds.size(); i++) {
                FreezeFrameData ff = ours.get(i).freezeFrame();
                assertEquals(FreezeFrameData.Availability.READ, ff.availability(), address + " " + i);
                List<String> fields = ff.fields().stream().map(x -> x.kind().equals("ENVIRONMENT") ? x.value()
                        : VCDS_LABELS.get(x.kind()) + ": " + x.value()).toList();
                List<String> expected = vcds.get(i).freezeFrameFields().stream().filter(x -> !x.label().equals("Date"))
                        .map(VcdsLog.Field::toString).toList();
                assertEquals(expected, fields, address + " " + vcds.get(i).number());
                records++;
            }
        }
        assertEquals(14, records);
    }

    @Test
    @DisplayName("UDS freeze frames of 17 and 77 read with 19 06 (only), records kept raw")
    void udsFreezeFrames() {
        for (String address : List.of("17", "77")) {
            for (FaultCode f : result.module(ModuleAddress.parse(address)).orElseThrow().faults()) {
                FreezeFrameData ff = f.freezeFrame();
                assertEquals(FreezeFrameData.Availability.READ, ff.availability(), address + " " + f.code());
                assertEquals(1, ff.records().size());
                assertTrue(ff.records().getFirst().id().startsWith("19 06 "), ff.records().toString());
            }
        }
        assertTrue(audit.lines().noneMatch(l -> l.contains("\"request\":\"19 04")), "no 19 04, like VCDS");
    }

    @Test
    @DisplayName("part numbers of the 13 responding modules = VCDS's 'Part No'")
    void identification() {
        int compared = 0;
        for (VcdsLog.Module vm : VCDS.modules()) {
            if (vm.cannotBeReached()) {
                continue;
            }
            EcuInfo id = result.module(ModuleAddress.parse(vm.address())).orElseThrow().identification().orElseThrow();
            assertEquals(vm.partNumber().orElseThrow().replace(" ", ""), id.partNumber().orElseThrow(), vm.address());
            compared++;
        }
        assertEquals(13, compared);
    }

    @Test
    @DisplayName("mileage = VCDS's header 'Mileage: 366280km-227595mi', from 17 Instruments")
    void mileage() {
        Mileage m = result.mileage().orElseThrow();
        assertEquals(VCDS.mileageKm().orElseThrow(), m.kilometres());
        assertEquals(227595, m.miles());
        assertEquals(ModuleAddress.INSTRUMENTS, m.source());
        assertEquals("62 22 03 8F 14", m.raw().hex());
    }

    @Test
    @DisplayName("every request sent passed the allowlist and was one VCDS sent too (no VIN reads)")
    void requests() {
        assertEquals(List.of(), ecu.protocolErrors());
        List<String> sent = new ArrayList<>();
        for (String line : audit.lines().filter(l -> l.contains("\"event\":\"request\"")).toList()) {
            String request = line.replaceAll(".*\"request\":\"([0-9A-F ]+)\".*", "$1");
            String module = line.replaceAll(".*\"module\":\"(\\w\\w)\".*", "$1");
            Protocol p = result.module(ModuleAddress.parse(module)).orElseThrow().link().orElseThrow().protocol();
            assertTrue(RequestGuard.isAllowed(p == Protocol.KWP2000
                    ? io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol.KWP2000
                    : io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol.UDS, Hex.parse(request)), line);
            sent.add(module + " " + request);
        }
        assertEquals(sent.size(), ecu.answered().size() + ecu.derived().size() + ecu.unseen().size(),
                "every request reached the ECU once");
        assertTrue(sent.size() >= 100, "requests: " + sent.size());
        assertTrue(sent.stream().noneMatch(s -> s.endsWith(" 3E")), "no KWP 3E on TP 2.0 (VCDS sends none)");

        Map<String, List<String>> unseen = new LinkedHashMap<>();
        ecu.unseen().forEach(u -> unseen.computeIfAbsent(u.link().toString(), k -> new ArrayList<>()).add(u.request() + " -> " + u.answer()));
        assertEquals(Map.of(), unseen, "nothing VCDS didn't send");
        assertTrue(sent.stream().noneMatch(s -> s.endsWith(" 1A 90") || s.endsWith(" 22 F1 90")), "the VIN is never read");
        assertEquals(Set.of("TP2.0 03", "TP2.0 20", "TP2.0 1F"),
                Set.copyOf(ecu.derived().stream().map(d -> d.link().toString()).toList()),
                "F187 / F189 / F191 from VCDS's six-DID read");
        assertTrue(ecu.links().contains(new CaptureExchanges.Link.Tp20(0x1F)));
    }

    @Test
    @DisplayName("the text report has every module, status and fault number VCDS shows (written to build/ for reading)")
    void report() throws IOException {
        String report = ScanReport.text(result);
        assertTrue(report.contains("Mileage:      366280 km (227595 mi), from module 17\n"), report);
        assertTrue(!report.contains("VIN"), report);
        for (VcdsLog.StatusLine s : VCDS.statusTable()) {
            assertTrue(report.contains(s.address()), s.address());
            assertTrue(report.contains(s.text()), s.text());
        }
        for (VcdsLog.Module m : VCDS.modules()) {
            for (VcdsLog.Fault f : m.faults()) {
                String shown = f.code().filter(c -> m.address().equals("17") || m.address().equals("77")).orElse(f.number());
                assertTrue(report.contains(f.number().equals("0022") ? "P0000" : shown), m.address() + " " + shown);
            }
        }
        assertTrue(!report.contains(ScanReport.DEMO_BANNER));
        Path out = Path.of("build", "replay", "autoscan-2026-11-13.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }
}
