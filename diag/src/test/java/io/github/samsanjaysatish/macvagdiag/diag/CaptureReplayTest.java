package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.RealCaptures;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.VcdsLog;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.CaptureEcu;
import io.github.samsanjaysatish.macvagdiag.transport.ChannelFactory;
import io.github.samsanjaysatish.macvagdiag.transport.DiagnosticChannel;
import io.github.samsanjaysatish.macvagdiag.transport.ModuleRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * diag on the real 2026-11-13 car: the real KWP2000 / UDS services over real transport channels, with the module
 * answers coming from the capture ({@link CaptureEcu}), compared with the VCDS log of the same scan. For every
 * module VCDS reached: session, identification, fault codes with VCDS's numbers / P- and SAE codes / status words,
 * and freeze frames (KWP2000 decoded field by field; UDS against the record-layout hypothesis in
 * {@code diag/CLAUDE.md}); and the gateway installation list against VCDS's status table.
 *
 * <p>Known differences, not compared: VCDS's fault and failure-type texts (its database; two UDS failure types
 * differ from the DTC byte), freeze-frame dates (KWP: not decodable; UDS: VCDS printed the laptop's wrong date).
 */
class CaptureReplayTest {

    private static final VcdsLog SCAN = VcdsLog.parse(RealCaptures.autoScanVcdsLog());

    /** VCDS address → how we reach it (from the capture; scan's {@code ModuleAddress} has the same table). */
    private static final Map<String, ModuleRoute> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("01", new ModuleRoute.Tp20(0x01));
        ROUTES.put("02", new ModuleRoute.Tp20(0x02));
        ROUTES.put("03", new ModuleRoute.Tp20(0x03));
        ROUTES.put("05", new ModuleRoute.IsoTp(0x732, 0x79C, ApplicationProtocol.UDS));
        ROUTES.put("08", new ModuleRoute.Tp20(0x2C));
        ROUTES.put("09", new ModuleRoute.Tp20(0x20));
        ROUTES.put("15", new ModuleRoute.IsoTp(0x715, 0x77F, ApplicationProtocol.UDS));
        ROUTES.put("17", new ModuleRoute.IsoTp(0x714, 0x77E, ApplicationProtocol.UDS));
        ROUTES.put("19", new ModuleRoute.Tp20(0x1F));
        ROUTES.put("25", new ModuleRoute.IsoTp(0x711, 0x77B, ApplicationProtocol.UDS));
        ROUTES.put("2B", new ModuleRoute.IsoTp(0x731, 0x79B, ApplicationProtocol.UDS));
        ROUTES.put("44", new ModuleRoute.Tp20(0x09));
        ROUTES.put("77", new ModuleRoute.IsoTp(0x76B, 0x7D5, ApplicationProtocol.UDS));
    }

    /** One module read the way scan reads it, on one channel. */
    private record Read(DiagResult<RawBytes> session, DiagResult<EcuIdentification> identification,
            DiagResult<DtcList> dtcs, List<DiagResult<FreezeFrame>> freezeFrames, CaptureEcu ecu) {
    }

    private static Read read(String address, Capture... captures) throws InterruptedException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        CaptureEcu ecu = CaptureEcu.of(captures);
        SimulatedAdapter adapter = new SimulatedAdapter(time, ecu);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        ModuleRoute route = ROUTES.get(address);
        try (DiagnosticChannel ch = ChannelFactory.create(adapter, time).open(route)) {
            KwpOptions options = address.equals("01") ? KwpOptions.ENGINE : KwpOptions.DEFAULT;
            DiagnosticService diag = DiagnosticService.forChannel(ch, time, DiagTimings.DEFAULTS, options);
            DiagResult<RawBytes> session = diag.startSession(route.protocol() == ApplicationProtocol.KWP2000
                    ? DiagnosticSession.KWP_VW_DIAGNOSTIC : DiagnosticSession.UDS_EXTENDED);
            DiagResult<EcuIdentification> id = diag.readIdentification();
            DiagResult<DtcList> dtcs = diag.readDtcs();
            List<DiagResult<FreezeFrame>> ffs = new ArrayList<>();
            for (Dtc d : dtcs.valueIfSuccess().map(DtcList::dtcs).orElse(List.of())) {
                ffs.add(diag.readFreezeFrame(d));
            }
            assertEquals(List.of(), ecu.protocolErrors(), address);
            return new Read(session, id, dtcs, ffs, ecu);
        }
    }

    private static Read read(String address) throws InterruptedException {
        return read(address, RealCaptures.autoScan());
    }

    private static <T> T ok(DiagResult<T> r, String what) {
        assertTrue(r.isSuccess(), what + ": " + r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    private static String noSpaces(String s) {
        return s.replace(" ", "");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"01", "02", "03", "05", "08", "09", "15", "17", "19", "25", "2B", "44", "77"})
    @DisplayName("identification of every reachable module matches VCDS: part number, component, software, HW, coding")
    void identification(String address) throws InterruptedException {
        Read r = read(address);
        ok(r.session(), address + " session");
        EcuIdentification id = ok(r.identification(), address + " identification");
        VcdsLog.Module vcds = SCAN.module(address).orElseThrow();
        assertEquals(noSpaces(vcds.partNumber().orElseThrow()), id.partNumber().orElseThrow(), address + " part number");
        String component = vcds.component().orElseThrow();
        assertTrue(component.startsWith(id.systemName().orElseThrow()),
                address + ": VCDS component '" + component + "' starts with our name '" + id.systemName().orElseThrow() + "'");
        id.softwareVersion().ifPresent(sw -> assertTrue(component.endsWith(sw),
                address + ": VCDS component '" + component + "' ends with our software version " + sw));
        id.hardwareNumber().ifPresent(hw -> assertEquals(noSpaces(vcds.info("HW").orElseThrow()), hw, address + " HW"));
        vcds.coding().ifPresent(c -> assertEquals(c, id.coding().orElseThrow(), address + " coding"));
        if (vcds.coding().isEmpty()) {
            assertEquals(Optional.empty(), id.coding(), address + ": VCDS shows no coding");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"01", "02", "03", "05", "08", "09", "15", "17", "19", "25", "2B", "44", "77"})
    @DisplayName("fault codes of every reachable module match VCDS: numbers, P/SAE codes, fault types, status words")
    void faultCodes(String address) throws InterruptedException {
        Read r = read(address);
        DtcList list = ok(r.dtcs(), address + " DTCs");
        assertTrue(list.complete(), address);
        List<VcdsLog.Fault> vcds = SCAN.module(address).orElseThrow().faults();
        assertEquals(vcds.size(), list.dtcs().size(), address + " count");
        for (int i = 0; i < vcds.size(); i++) {
            VcdsLog.Fault v = vcds.get(i);
            String what = address + " fault " + i + " (" + v.number() + ")";
            switch (list.dtcs().get(i)) {
                case KwpDtc k -> {
                    assertEquals(v.number(), k.displayCode(), what);
                    assertEquals(v.code(), k.obdCode(), what);
                    assertEquals(v.faultType().orElseThrow(), k.status().faultTypeText(), what);
                    assertEquals(v.flags(), k.status().flagNames(), what);
                }
                case UdsDtc u -> {
                    assertEquals(v.number(), u.vagNumber(), what);
                    if (!KNOWN_CODE_DIFFERENCES.contains(v.number())) {
                        assertEquals(v.code().orElseThrow(), u.displayCode(), what);
                    }
                    assertEquals(v.statusBits().orElseThrow(), bits(u.status().raw()), what);
                    assertEquals(v.flags(), u.status().vcdsWords(), what);
                    if (!KNOWN_FAILURE_TYPE_DIFFERENCES.contains(v.number())) {
                        assertEquals(v.failureType().orElseThrow(), String.format("%02X", u.failureType()), what);
                    }
                }
                default -> throw new AssertionError(what);
            }
        }
    }

    /**
     * VCDS prints a failure type from its database: {@code 90 3E 12} as "B103E 1B" and {@code 00 00 16} as
     * "U1066 00". Every other UDS fault shows the DTC's third byte.
     */
    private static final java.util.Set<String> KNOWN_FAILURE_TYPE_DIFFERENCES = java.util.Set.of("9453074", "0022");
    /** {@code 00 00 16} is SAE {@code P0000} by J2012; VCDS shows "U1066" from its database. */
    private static final java.util.Set<String> KNOWN_CODE_DIFFERENCES = java.util.Set.of("0022");

    private static String bits(int b) {
        return String.format("%8s", Integer.toBinaryString(b)).replace(' ', '0');
    }

    /** Our KWP freeze-frame field kinds under VCDS's labels. */
    private static final Map<FreezeFrame.Field.Kind, String> VCDS_LABELS = Map.of(
            FreezeFrame.Field.Kind.STATUS, "Fault Status",
            FreezeFrame.Field.Kind.PRIORITY, "Fault Priority",
            FreezeFrame.Field.Kind.OCCURRENCE_COUNTER, "Fault Frequency",
            FreezeFrame.Field.Kind.RESET_COUNTER, "Reset counter",
            FreezeFrame.Field.Kind.MILEAGE, "Mileage",
            FreezeFrame.Field.Kind.TIME_INDICATION, "Time Indication",
            FreezeFrame.Field.Kind.TIME, "Time");

    private static List<String> asVcds(FreezeFrame ff) {
        return ff.fields().stream().map(f -> f.kind() == FreezeFrame.Field.Kind.ENVIRONMENT ? f.value()
                : VCDS_LABELS.get(f.kind()) + ": " + f.value()).toList();
    }

    private static List<String> vcdsWithoutDate(VcdsLog.Fault f) {
        return f.freezeFrameFields().stream().filter(x -> !x.label().equals("Date")).map(VcdsLog.Field::toString).toList();
    }

    @Test
    @DisplayName("Auto-Scan, 01 Engine: all 12 KWP freeze frames equal VCDS's, field for field (84 environment values)")
    void engineFreezeFramesAutoScan() throws InterruptedException {
        kwpFreezeFrames("01", SCAN, RealCaptures.autoScan());
    }

    @Test
    @DisplayName("01 fault-code read: the same 12 freeze frames from the second capture equal its VCDS log")
    void engineFreezeFramesSecondCapture() throws InterruptedException {
        kwpFreezeFrames("01", VcdsLog.parse(RealCaptures.freezeFrame01VcdsLog()), RealCaptures.freezeFrame01());
    }

    @Test
    @DisplayName("19 Gateway: its 2 KWP freeze frames (no environment values) equal VCDS's")
    void gatewayFreezeFrames() throws InterruptedException {
        kwpFreezeFrames("19", SCAN, RealCaptures.autoScan());
    }

    private static void kwpFreezeFrames(String address, VcdsLog log, Capture capture) throws InterruptedException {
        Read r = read(address, capture);
        List<VcdsLog.Fault> vcds = log.module(address).orElseThrow().faults();
        assertEquals(vcds.size(), r.freezeFrames().size());
        int environment = 0;
        for (int i = 0; i < vcds.size(); i++) {
            FreezeFrame ff = ok(r.freezeFrames().get(i), address + " freeze frame " + i);
            assertEquals(vcdsWithoutDate(vcds.get(i)), asVcds(ff), address + " fault " + i + " (" + vcds.get(i).number() + ")");
            assertEquals(List.of(), ff.notes(), address + " fault " + i);
            environment += (int) ff.fields().stream().filter(f -> f.kind() == FreezeFrame.Field.Kind.ENVIRONMENT).count();
        }
        assertEquals(address.equals("01") ? 84 : 0, environment);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"17", "77"})
    @DisplayName("UDS freeze frames (19 06) fit the layout hypothesis: priority, frequency, reset counter, mileage, time as VCDS")
    void udsFreezeFrames(String address) throws InterruptedException {
        Read r = read(address);
        List<VcdsLog.Fault> vcds = SCAN.module(address).orElseThrow().faults();
        assertEquals(vcds.size(), r.freezeFrames().size());
        for (int i = 0; i < vcds.size(); i++) {
            FreezeFrame ff = ok(r.freezeFrames().get(i), address + " freeze frame " + i);
            assertEquals(1, ff.records().size());
            assertTrue(ff.records().getFirst().id().startsWith("19 06 "));
            assertEquals(vcdsWithoutDate(vcds.get(i)), udsHypothesis(ff.records().getFirst().data()),
                    address + " fault " + i + " (" + vcds.get(i).number() + ")");
        }
    }

    /**
     * The hypothesis from {@code diag/CLAUDE.md}: {@code 01 [priority] [frequency] 02 [reset counter] [mileage km, 3]
     * [time indication] [5 bytes date/time]}, {@code FF} where not available; time = low 17 bits of the last 3 bytes
     * (hour 5, minute 6, second 6), printed by VCDS without the KWP +1 s. VCDS shows mileage and time only when set.
     */
    private static List<String> udsHypothesis(RawBytes data) {
        int[] b = new int[data.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = data.byteAt(i);
        }
        List<String> out = new ArrayList<>();
        assertEquals(0x01, b[0]);
        out.add("Fault Priority: " + b[1]);
        out.add("Fault Frequency: " + b[2]);
        assertEquals(0x02, b[3]);
        out.add("Reset counter: " + b[4]);
        int km = (b[5] << 16) | (b[6] << 8) | b[7];
        if (km != 0xFFFFFF) {
            out.add("Mileage: " + km + " km");
        }
        int t = ((b[11] << 16) | (b[12] << 8) | b[13]) & 0x1FFFF;
        if (b.length >= 14 && !(b[9] == 0xFF && b[10] == 0xFF && b[11] == 0xFF)) {
            out.add(String.format("Time: %02d:%02d:%02d", t >> 12, (t >> 6) & 0x3F, t & 0x3F));
        }
        return out;
    }

    @Test
    @DisplayName("gateway installation list (22 04 A1): the 16 installed modules and their status bits are VCDS's status table")
    void installationList() throws InterruptedException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time, CaptureEcu.of(RealCaptures.autoScan()));
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        InstallationList list;
        try (DiagnosticChannel ch = ChannelFactory.create(adapter, time).open(ROUTES.get("19"))) {
            DiagnosticService diag = DiagnosticService.forChannel(ch, time);
            ok(diag.startSession(DiagnosticSession.KWP_VW_DIAGNOSTIC), "10 89");
            list = ok(diag.readInstallationList(), "22 04 A1");
        }
        assertTrue(list.complete());
        assertEquals(35, list.entries().size());
        assertEquals(SCAN.statusTable().stream().map(VcdsLog.StatusLine::address).toList(),
                list.installed().stream().map(e -> String.format("%02X", e.address())).sorted().toList());
        for (VcdsLog.StatusLine s : SCAN.statusTable()) {
            InstallationList.Entry e = list.entry(Integer.parseInt(s.address(), 16)).orElseThrow();
            assertEquals(s.bits(), e.vcdsStatusBits(), s.address());
        }
    }

    @Test
    @DisplayName("what VCDS never asked: 1A 90 (VIN) on the KWP modules, F190 on the UDS modules; 03/09/19 DIDs from the multi-DID read")
    void requestsTheCaptureNeverSaw() throws InterruptedException {
        Map<String, List<String>> unseen = new LinkedHashMap<>();
        Map<String, List<String>> derived = new LinkedHashMap<>();
        for (String address : ROUTES.keySet()) {
            CaptureEcu ecu = read(address).ecu();
            List<String> u = ecu.unseen().stream().map(x -> x.request() + " -> " + x.answer()).toList();
            if (!u.isEmpty()) {
                unseen.put(address, u);
            }
            List<String> d = ecu.derived().stream().map(CaptureEcu.Answered::request).toList();
            if (!d.isEmpty()) {
                derived.put(address, d);
            }
        }
        assertEquals(Map.of("03", List.of("22 F1 87", "22 F1 89", "22 F1 91"), "09", List.of("22 F1 87", "22 F1 89", "22 F1 91"),
                "19", List.of("22 F1 89")), derived, "VCDS read these only inside 22 F1 87 F1 89 F1 91 F1 A3 F1 A5 F1 DF");
        List<String> uds = List.of("22 F1 90 -> 7F 22 31");
        List<String> kwpVin = List.of("1A 90 -> 7F 1A 12");
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("01", kwpVin);
        expected.put("02", kwpVin);
        expected.put("05", uds);
        expected.put("08", kwpVin);
        expected.put("15", uds);
        expected.put("17", uds);
        expected.put("25", uds);
        expected.put("2B", uds);
        expected.put("44", kwpVin);
        expected.put("77", uds);
        assertEquals(expected, unseen);
        assertInstanceOf(DiagResult.Success.class, read("17").session());
    }
}
