package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The KWP2000 exchanges of the 2026-11-13 capture ({@link CaptureBytes}), decoded and compared with what the
 * VCDS logs of the same session print for the same modules.
 */
class KwpCaptureTest {

    private final FakeTimeSource time = new FakeTimeSource();

    private KwpDiagnosticService service(FakeDiagnosticChannel ch, KwpOptions options) {
        return new KwpDiagnosticService(ch, time, DiagTimings.DEFAULTS, options);
    }

    private static <T> T ok(DiagResult<T> r) {
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    /** One VCDS line pair per engine fault: code, P-code - type, Fault Status bits, flag words. */
    private record VcdsFault(String code, String pCode, String type, String bits, List<String> flags) {
    }

    private static final List<VcdsFault> ENGINE_VCDS = List.of(
            new VcdsFault("011174", "P2BA6", "000", "11100000", List.of("MIL ON")),
            new VcdsFault("006744", "P1A58", "000", "01100000", List.of()),
            new VcdsFault("006470", "P1946", "000", "00100000", List.of("Intermittent")),
            new VcdsFault("001025", "P0401", "001", "01100001", List.of()),
            new VcdsFault("009231", "P240F", "000", "11100000", List.of("MIL ON")),
            new VcdsFault("001681", "P0691", "000", "00100000", List.of("Intermittent")),
            new VcdsFault("001348", "P0544", "000", "00100000", List.of("Intermittent")),
            new VcdsFault("001025", "P0401", "002", "00100010", List.of("Intermittent")),
            new VcdsFault("001348", "P0544", "001", "00100001", List.of("Intermittent")),
            new VcdsFault("000135", "P0087", "001", "00100001", List.of("Intermittent")),
            new VcdsFault("000135", "P0087", "002", "01100010", List.of()),
            new VcdsFault("000135", "P0087", "006", "00100110", List.of("Intermittent")));

    private DtcList engineDtcs(FakeDiagnosticChannel ch) throws InterruptedException {
        ch.on("18 00 FF 00", CaptureBytes.ENGINE_DTCS);
        return ok(service(ch, KwpOptions.ENGINE).readDtcs());
    }

    @Test
    @DisplayName("01 Engine: 18 00 FF 00 -> 12 codes, P-codes, fault types, status bits and words exactly as VCDS")
    void engineDtcs() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp();
        DtcList l = engineDtcs(ch);
        assertEquals(List.of("18 00 FF 00"), ch.requestsHex());
        assertTrue(l.complete(), l.warnings().toString());
        assertEquals(ENGINE_VCDS.size(), l.dtcs().size());
        for (int i = 0; i < ENGINE_VCDS.size(); i++) {
            VcdsFault v = ENGINE_VCDS.get(i);
            KwpDtc d = (KwpDtc) l.dtcs().get(i);
            String at = "entry " + i + " " + d;
            assertEquals(v.code(), d.displayCode(), at);
            assertEquals(Optional.of(v.pCode()), d.obdCode(), at);
            assertEquals(v.type(), d.status().faultTypeText(), at);
            assertEquals(v.bits(), d.status().bits(), at);
            assertEquals(v.flags(), d.status().flagNames(), at);
        }
    }

    @Test
    @DisplayName("19 Gateway: 18 02 FF 00 -> 01304 and 02078, type 004, no words, no P-code")
    void gatewayDtcs() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02 FF 00", CaptureBytes.GATEWAY_DTCS);
        DtcList l = ok(service(ch, KwpOptions.DEFAULT).readDtcs());
        assertEquals(List.of("18 02 FF 00"), ch.requestsHex());
        assertEquals(List.of("01304", "02078"), l.dtcs().stream().map(Dtc::displayCode).toList());
        for (Dtc d : l.dtcs()) {
            KwpDtc k = (KwpDtc) d;
            assertEquals(Optional.empty(), k.obdCode());
            assertEquals("004", k.status().faultTypeText());
            assertEquals("01100100", k.status().bits());
            assertEquals(List.of(), k.status().flagNames());
        }
    }

    /** VCDS's freeze-frame block for one engine fault: the 7 header lines, then the environment lines. */
    private record VcdsFreezeFrame(String status, int priority, int frequency, int reset, int km, String time,
            List<String> environment) {
    }

    private static final List<String> ENV_1 = List.of("RPM: 828 /min", "Speed: 0.0 km/h", "Load: 0.0 %",
            "Pressure: 1004.3 mbar", "Temperature: 13.5°C", "Mass Air / Rev.: 260.0 mg/str", "Temperature: 48.6°C");

    private static final List<VcdsFreezeFrame> ENGINE_FREEZE_VCDS = List.of(
            new VcdsFreezeFrame("11100000", 2, 254, 255, 362256, "09:13:31", ENV_1),
            new VcdsFreezeFrame("01100000", 6, 255, 255, 362256, "09:13:32", ENV_1),
            new VcdsFreezeFrame("00100000", 2, 255, 255, 362256, "09:13:44", List.of("RPM: 828 /min", "Speed: 0.0 km/h",
                    "Temperature: 48.6°C", "Temperature: 96.3°C", "Temperature: 13.5°C", "Lambda: 34.7 %", "Lambda: 0.0 %")),
            new VcdsFreezeFrame("01100001", 2, 254, 255, 362256, "09:14:01", List.of("RPM: 805 /min", "Speed: 0.0 km/h",
                    "Voltage: 13.83 V", "Load: 71.8 %", "Load: 100.0 %", "Mass Air / Rev.: 225.0 mg/str",
                    "Mass Air / Rev.: 350.0 mg/str")),
            new VcdsFreezeFrame("11100000", 2, 2, 255, 362263, "09:31:21", List.of("RPM: 1472 /min", "Speed: 75.0 km/h",
                    "Load: 69.0 %", "Mass Air / Rev.: 0.0 mg/str", "Mass Air / Rev.: 145.0 mg/str",
                    "Mass Air / Rev.: 275.0 mg/str", "Mass Air / Rev.: 440.0 mg/str")),
            new VcdsFreezeFrame("00100000", 2, 66, 255, 362340, "18:08:13", List.of("RPM: 1794 /min", "Speed: 39.0 km/h",
                    "Temperature: 18.9°C", "Temperature: 74.7°C", "Temperature: 15.3°C", "Lambda: 9.5 %", "Lambda: 0.0 %")),
            new VcdsFreezeFrame("00100000", 2, 14, 255, 363948, "12:38:57", List.of("RPM: 2990 /min", "Speed: 93.0 km/h",
                    "Voltage: 13.83 V", "Temperature: 88.2°C", "Temperature: 930.0°C", "Temperature: 516.0°C",
                    "Temperature: 444.0°C")),
            new VcdsFreezeFrame("00100010", 2, 7, 255, 365708, "16:37:56", List.of("RPM: 1541 /min", "Speed: 64.0 km/h",
                    "Voltage: 13.91 V", "Load: 92.9 %", "Load: 100.0 %", "Mass Air / Rev.: 360.0 mg/str",
                    "Mass Air / Rev.: 515.0 mg/str")),
            new VcdsFreezeFrame("00100001", 2, 29, 255, 363948, "12:38:53", List.of("RPM: 1725 /min", "Speed: 88.0 km/h",
                    "Voltage: 13.76 V", "Temperature: 96.3°C", "Temperature: 930.0°C", "Temperature: 534.0°C",
                    "Temperature: 420.0°C")),
            new VcdsFreezeFrame("00100001", 2, 254, 255, 366163, "15:38:40", List.of("RPM: 1633 /min",
                    "Inj. Quantity: 42.8 mg/str", "(no units): 5.0", "Pressure: 1368.0 bar", "Pressure: 176.0 bar",
                    "Temperature: 60.3°C", "Tank Cont.: 2.8 l")),
            new VcdsFreezeFrame("01100010", 2, 1, 255, 366163, "15:38:41", List.of("RPM: 1886 /min",
                    "Inj. Quantity: 25.0 mg/str", "(no units): 5.0", "Pressure: 1376.0 bar", "Pressure: 264.0 bar",
                    "Temperature: 60.3°C", "Tank Cont.: 2.8 l")),
            new VcdsFreezeFrame("00100110", 2, 1, 255, 366164, "15:38:47", List.of("RPM: 2576 /min",
                    "Inj. Quantity: 20.3 mg/str", "(no units): 4.0", "Pressure: 1488.0 bar", "Pressure: 80.0 bar",
                    "Temperature: 60.3°C", "Tank Cont.: 2.8 l")));

    private static List<String> values(FreezeFrame ff, FreezeFrame.Field.Kind kind) {
        return ff.fields().stream().filter(f -> f.kind() == kind).map(FreezeFrame.Field::value).toList();
    }

    private static void assertHeader(VcdsFreezeFrame v, FreezeFrame ff, String at) {
        assertEquals(List.of(v.status()), values(ff, FreezeFrame.Field.Kind.STATUS), at);
        assertEquals(List.of(Integer.toString(v.priority())), values(ff, FreezeFrame.Field.Kind.PRIORITY), at);
        assertEquals(List.of(Integer.toString(v.frequency())), values(ff, FreezeFrame.Field.Kind.OCCURRENCE_COUNTER), at);
        assertEquals(List.of(Integer.toString(v.reset())), values(ff, FreezeFrame.Field.Kind.RESET_COUNTER), at);
        assertEquals(List.of(v.km() + " km"), values(ff, FreezeFrame.Field.Kind.MILEAGE), at);
        assertEquals(List.of("0"), values(ff, FreezeFrame.Field.Kind.TIME_INDICATION), at);
        assertEquals(List.of(v.time()), values(ff, FreezeFrame.Field.Kind.TIME), at);
        assertEquals(List.of(), values(ff, FreezeFrame.Field.Kind.DATE_TIME), "date not decoded " + at);
    }

    @Test
    @DisplayName("01 Engine: 12 00 04 per list entry, in order, repeats for 04 01 / 05 44 / 00 87: all 12 records as VCDS")
    void engineFreezeFrames() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp();
        DtcList l = engineDtcs(ch);
        List<String> expectedRequests = new ArrayList<>(List.of("18 00 FF 00"));
        for (int i = 0; i < l.dtcs().size(); i++) {
            String request = "12 00 04 " + l.dtcs().get(i).codeBytes().hex();
            ch.on(request, CaptureBytes.ENGINE_FREEZE_FRAMES.get(i));
            expectedRequests.add(request);
        }
        KwpDiagnosticService k = service(ch, KwpOptions.ENGINE);
        for (int i = 0; i < l.dtcs().size(); i++) {
            Dtc dtc = l.dtcs().get(i);
            FreezeFrame ff = ok(k.readFreezeFrame(dtc));
            VcdsFreezeFrame v = ENGINE_FREEZE_VCDS.get(i);
            String at = "entry " + i + " " + dtc;
            assertEquals(dtc, ff.dtc());
            assertHeader(v, ff, at);
            assertEquals(v.environment(), values(ff, FreezeFrame.Field.Kind.ENVIRONMENT), at);
            assertEquals(List.of(), ff.notes(), at);
            assertEquals(List.of(RawBytes.ofHex(CaptureBytes.ENGINE_FREEZE_FRAMES.get(i))), ff.responses(), at);
            String request = "12 00 04 " + dtc.codeBytes().hex();
            assertEquals(1, ff.records().size());
            assertEquals(request, ff.records().getFirst().id());
            assertTrue(ff.fields().stream().allMatch(f -> f.sourceRecordId().equals(request)));
        }
        assertEquals(expectedRequests, ch.requestsHex());
        assertTrue(ch.allScriptUsed());
    }

    @Test
    @DisplayName("19 Gateway: 12 00 04 05 18 / 08 1E -> no environment values, header as VCDS (18:02:50, 18:02:51)")
    void gatewayFreezeFrames() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("18 02 FF 00", CaptureBytes.GATEWAY_DTCS)
                .on("12 00 04 05 18", CaptureBytes.GATEWAY_FREEZE_FRAMES.get(0))
                .on("12 00 04 08 1E", CaptureBytes.GATEWAY_FREEZE_FRAMES.get(1));
        KwpDiagnosticService k = service(ch, KwpOptions.DEFAULT);
        DtcList l = ok(k.readDtcs());
        List<String> times = List.of("18:02:50", "18:02:51");
        for (int i = 0; i < 2; i++) {
            FreezeFrame ff = ok(k.readFreezeFrame(l.dtcs().get(i)));
            assertHeader(new VcdsFreezeFrame("01100100", 2, 1, 240, 359052, times.get(i), List.of()), ff, "gateway " + i);
            assertEquals(List.of(), values(ff, FreezeFrame.Field.Kind.ENVIRONMENT));
            assertEquals(List.of(), ff.notes());
        }
        assertEquals(List.of("18 02 FF 00", "12 00 04 05 18", "12 00 04 08 1E"), ch.requestsHex());
    }

    @Test
    @DisplayName("1A 9B blocks of 01, 02, 08, 44: part number, software version, coding (or none), component as VCDS")
    void identificationBlocks() throws InterruptedException {
        record Expected(String response, String part, String sw, Optional<String> coding, String component) {
        }
        List<Expected> all = List.of(
                new Expected(CaptureBytes.ENGINE_ID_9B, "03L997030M", "9983", Optional.of("0050078"), "R4 2.0l TDI G000AG"),
                new Expected(CaptureBytes.TRANS_ID_9B, "02E300062B", "9430", Optional.of("0000020"), "GSG DSG AG6     512"),
                new Expected(CaptureBytes.HVAC_ID_9B, "5C0820047BA", "0204", Optional.empty(), "J301  Klimaanlage"),
                new Expected(CaptureBytes.STEERING_ID_9B, "1K0909144P", "3305", Optional.empty(), "EPS_ZFLS Kl. 70"));
        for (Expected e : all) {
            FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", e.response()).on("1A 90", "7F 1A 12");
            EcuIdentification id = ok(service(ch, KwpOptions.DEFAULT).readIdentification());
            assertEquals(List.of("1A 9B", "1A 90"), ch.requestsHex());
            assertEquals(Optional.of(e.part()), id.partNumber(), e.part());
            assertEquals(Optional.of(e.sw()), id.softwareVersion(), e.part());
            assertEquals(e.coding(), id.coding(), e.part());
            assertEquals(Optional.of(e.component()), id.systemName(), e.part());
            assertEquals(Optional.empty(), id.vin());
            assertEquals(new RawRecord("1A 9B", RawBytes.ofHex(e.response().substring("5A 9B ".length()))),
                    id.records().getFirst());
            assertEquals(1, id.notes().size(), id.notes().toString()); // the 1A 90 refusal
        }
    }

    @Test
    @DisplayName("19 Gateway: 1A 9B -> 7F 1A 11, then 22 F1 87 / F1 89 / F1 91 / F1 97 / 06 00 on the KWP channel")
    void gatewayIdentificationByDid() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("1A 9B", "7F 1A 11")
                .on("22 F1 87", CaptureBytes.GATEWAY_F187)
                .on("22 F1 89", CaptureBytes.GATEWAY_F189_FROM_MULTI_DID)
                .on("22 F1 91", CaptureBytes.GATEWAY_F191)
                .on("22 F1 97", CaptureBytes.GATEWAY_F197)
                .on("22 06 00", CaptureBytes.GATEWAY_0600);
        EcuIdentification id = ok(service(ch, KwpOptions.DEFAULT).readIdentification());
        assertEquals(List.of("1A 9B", "22 F1 87", "22 F1 89", "22 F1 91", "22 F1 97", "22 06 00"), ch.requestsHex());
        // VCDS: "Part No SW: 6N0 909 901  HW: 5C0 937 087 A", "Component: GW-K-CAN TP20 H66 0257", "Coding: 102200"
        assertEquals(Map.of(EcuIdentification.Field.PART_NUMBER, "6N0909901",
                EcuIdentification.Field.SOFTWARE_VERSION, "0257",
                EcuIdentification.Field.HARDWARE_NUMBER, "5C0937087A",
                EcuIdentification.Field.SYSTEM_NAME, "GW-K-CAN TP20",
                EcuIdentification.Field.CODING, "102200"), id.fields());
        assertEquals(5, id.records().size());
        assertEquals("22 F1 87", id.records().getFirst().id());
        assertEquals(1, id.notes().size());
        assertTrue(id.notes().getFirst().startsWith("1A 9B: Negative response 7F 1A 11"), id.notes().getFirst());
        assertTrue(id.notes().getFirst().contains("reading identification DIDs with 22 instead"));
    }

    @Test
    @DisplayName("19 Gateway: 22 04 A1 -> 35 entries, the 16 installed ones are VCDS's module list with its statuses")
    void installationList() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("22 04 A1", CaptureBytes.GATEWAY_INSTALLATION_LIST);
        InstallationList list = ok(service(ch, KwpOptions.DEFAULT).readInstallationList());
        assertEquals(List.of("22 04 A1"), ch.requestsHex());
        assertTrue(list.complete());
        assertEquals(35, list.entries().size());
        assertEquals(RawBytes.ofHex(CaptureBytes.GATEWAY_INSTALLATION_LIST), list.response());
        // VCDS Auto-Scan header lines: "01-Engine -- Status: Malfunction 0010" etc.
        Map<String, String> vcds = new java.util.LinkedHashMap<>();
        vcds.put("01", "0010");
        vcds.put("02", "0000");
        vcds.put("03", "0000");
        vcds.put("17", "0010");
        vcds.put("15", "0000");
        vcds.put("44", "0000");
        vcds.put("04", "0000");
        vcds.put("08", "0000");
        vcds.put("2E", "1100");
        vcds.put("56", "1100");
        vcds.put("77", "0010");
        vcds.put("09", "0000");
        vcds.put("19", "0010");
        vcds.put("25", "0000");
        vcds.put("05", "0000");
        vcds.put("2B", "0000");
        List<InstallationList.Entry> installed = list.installed();
        assertEquals(List.copyOf(vcds.keySet()), installed.stream().map(e -> String.format("%02X", e.address())).toList());
        for (InstallationList.Entry e : installed) {
            assertEquals(vcds.get(String.format("%02X", e.address())), e.vcdsStatusBits(), e.toString());
        }
        // TP 2.0 logical addresses (scan/CLAUDE.md table)
        assertEquals(0x07, list.entry(0x17).orElseThrow().tp20LogicalAddress());
        assertEquals(0x1F, list.entry(0x19).orElseThrow().tp20LogicalAddress());
        assertEquals(0x2C, list.entry(0x08).orElseThrow().tp20LogicalAddress());
        assertEquals(0x20, list.entry(0x09).orElseThrow().tp20LogicalAddress());
        assertEquals(Optional.empty(), list.entry(0x04).orElseThrow().tp20Address());
        assertEquals(InstallationList.Entry.Status.MALFUNCTION, list.entry(0x01).orElseThrow().statusKind());
        assertEquals(InstallationList.Entry.Status.OK, list.entry(0x02).orElseThrow().statusKind());
        assertEquals(InstallationList.Entry.Status.CANNOT_BE_REACHED, list.entry(0x56).orElseThrow().statusKind());
        assertEquals(Optional.empty(), list.entry(0x16), "16 isn't listed at all");
        assertEquals(InstallationList.Entry.Status.NOT_INSTALLED, list.entry(0x55).orElseThrow().statusKind());
        assertEquals(new InstallationList.Entry(0x2B, 0x30, 0x01, 0x31), list.entry(0x2B).orElseThrow());
    }
}
