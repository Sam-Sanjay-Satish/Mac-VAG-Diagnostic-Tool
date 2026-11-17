package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shared 2026-11-13 fixtures load, are complete, and carry only the masked VIN. */
class RealCapturesTest {

    private static final List<String> ALL = List.of(RealCaptures.AUTO_SCAN_CAPTURE, RealCaptures.FREEZE_FRAME_01_CAPTURE,
            RealCaptures.AUTO_SCAN_VCDS_LOG, RealCaptures.FREEZE_FRAME_01_VCDS_LOG);

    @Test
    @DisplayName("both captures load whole: 2535 and 454 frames, 500 kbit/s, listen-only, marked sanitized")
    void capturesLoad() {
        Capture scan = RealCaptures.autoScan();
        Capture ff = RealCaptures.freezeFrame01();
        assertEquals(2535, scan.frames().size());
        assertEquals(454, ff.frames().size());
        for (Capture c : List.of(scan, ff)) {
            assertEquals("500000", c.metadata().get("bitrate"));
            assertEquals("LISTEN_ONLY", c.metadata().get("mode"));
            assertTrue(c.metadata().getOrDefault("sanitized", "").contains("VIN replaced by " + RealCaptures.MASKED_VIN),
                    c.metadata().toString());
        }
        assertEquals(32, scan.frames().stream().map(f -> f.frame().id()).distinct().count(), "32 CAN IDs");
    }

    @Test
    @DisplayName("the VIN in the VCDS logs and in the captures' OBD 09 02 replies is the masked one")
    void onlyTheMaskedVin() {
        for (String log : List.of(RealCaptures.autoScanVcdsLog(), RealCaptures.freezeFrame01VcdsLog())) {
            Matcher m = Pattern.compile("VIN: (\\S+)").matcher(log);
            while (m.find()) {
                assertEquals(RealCaptures.MASKED_VIN, m.group(1));
            }
        }
        // 7E8 carries 49 02 01 [VIN] as ISO-TP FF + 2 CF: concatenate each reply's payload bytes.
        String masked = HexFormat.of().withUpperCase().formatHex(RealCaptures.MASKED_VIN.getBytes(StandardCharsets.US_ASCII));
        List<String> replies = new ArrayList<>();
        StringBuilder current = null;
        for (CapturedFrame f : RealCaptures.autoScan().frames()) {
            if (f.frame().id() != 0x7E8) {
                continue;
            }
            String hex = HexFormat.of().withUpperCase().formatHex(f.frame().data());
            if (hex.startsWith("10")) {
                current = new StringBuilder(hex.substring(4));
            } else if (hex.startsWith("2") && current != null) {
                current.append(hex.substring(2));
                if (hex.startsWith("22")) {
                    replies.add(current.toString());
                    current = null;
                }
            }
        }
        assertEquals(6, replies.size(), "VCDS asked for the VIN once per UDS module");
        for (String r : replies) {
            assertTrue(r.startsWith("490201" + masked), r);
        }
    }

    @Test
    @DisplayName("local only: no 8-character piece of the real VIN (from local/vcds-logs) in any fixture, as text or hex")
    void noPieceOfTheRealVin() throws IOException {
        Path log = Path.of("..", "local", "vcds-logs", "2026-11-13-150741-autoscan-2.vcds.txt");
        assumeTrue(Files.isRegularFile(log), "the raw VCDS log is only on the user's Mac (git-ignored local/)");
        Matcher m = Pattern.compile("VIN: ([A-HJ-NPR-Z0-9]{17})").matcher(Files.readString(log, StandardCharsets.ISO_8859_1));
        assertTrue(m.find(), "VIN line in the raw log");
        String vin = m.group(1);
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String resource : ALL) {
            String text = new String(RealCaptures.bytes(resource), StandardCharsets.ISO_8859_1).toUpperCase();
            String compact = text.replace(" ", "");
            for (int i = 0; i + 8 <= vin.length(); i++) {
                String piece = vin.substring(i, i + 8);
                String hex = HexFormat.of().withUpperCase().formatHex(piece.getBytes(StandardCharsets.US_ASCII));
                if (text.contains(piece) || compact.contains(hex)) {
                    hits.merge(resource, 1, Integer::sum);
                }
            }
        }
        // The message names files and counts only, never the VIN.
        assertTrue(hits.isEmpty(), "pieces of the real VIN found (not printed): " + hits);
    }

    @Test
    @DisplayName("the Auto-Scan VCDS log parses: 16 modules in the status table, 19 faults, 2E and 56 cannot be reached")
    void autoScanLogParses() {
        VcdsLog log = VcdsLog.parse(RealCaptures.autoScanVcdsLog());
        assertEquals(RealCaptures.MASKED_VIN, log.vin().orElseThrow());
        assertEquals(Optional.of(366280), log.mileageKm(), "header 'Mileage: 366280km-227595mi'");
        assertEquals(List.of("01", "02", "03", "04", "05", "08", "09", "15", "17", "19", "25", "2B", "2E", "44", "56", "77"),
                log.statusTable().stream().map(VcdsLog.StatusLine::address).toList());
        assertEquals("Malfunction 0010", log.status("01").orElseThrow().text());
        assertEquals("Cannot be reached 1100", log.status("56").orElseThrow().text());
        assertEquals("OK 0000", log.status("04").orElseThrow().text());
        assertEquals(List.of("01", "02", "03", "05", "08", "09", "15", "17", "19", "25", "2B", "2E", "44", "56", "77"),
                log.modules().stream().map(VcdsLog.Module::address).toList(), "04 has no block of its own");
        assertEquals(19, log.faultCount());
        assertTrue(log.module("2E").orElseThrow().cannotBeReached());
        assertTrue(log.module("56").orElseThrow().cannotBeReached());
        assertTrue(!log.module("01").orElseThrow().cannotBeReached());

        VcdsLog.Module engine = log.module("01").orElseThrow();
        assertEquals("Engine (CJA)", engine.name());
        assertEquals("03L 997 030 M", engine.partNumber().orElseThrow());
        assertEquals("03L 907 309 AA", engine.info("HW").orElseThrow());
        assertEquals("0050078", engine.coding().orElseThrow());
        assertEquals("R4 2.0l TDI G000AG  9983", engine.component().orElseThrow());
        assertEquals(12, engine.faults().size());
        VcdsLog.Fault first = engine.faults().getFirst();
        assertEquals("011174", first.number());
        assertEquals("NOx Emissions Exceeded", first.description());
        assertEquals("P2BA6", first.code().orElseThrow());
        assertEquals("000", first.faultType().orElseThrow());
        assertEquals("SCR Catalyst Malfunction", first.codeText());
        assertEquals(List.of("MIL ON"), first.flags());
        assertEquals(2, first.freezeFrames().size());
        assertEquals("11100000", first.freezeFrame("Fault Status").orElseThrow());
        assertEquals("362256 km", first.freezeFrame("Mileage").orElseThrow());
        assertEquals("09:13:31", first.freezeFrame("Time").orElseThrow());
        assertEquals(List.of("RPM: 828 /min", "Speed: 0.0 km/h", "Load: 0.0 %", "Pressure: 1004.3 mbar",
                "Temperature: 13.5°C", "Mass Air / Rev.: 260.0 mg/str", "Temperature: 48.6°C"),
                first.freezeFrames().get(1).stream().map(VcdsLog.Field::toString).toList());
        assertEquals(List.of("Intermittent"), engine.faults().get(2).flags());
        assertEquals(List.of(), engine.faults().get(1).flags());
        assertEquals("002", engine.faults().get(7).faultType().orElseThrow());

        VcdsLog.Fault gateway = log.module("19").orElseThrow().faults().getFirst();
        assertEquals("01304", gateway.number());
        assertTrue(gateway.code().isEmpty());
        assertEquals("004", gateway.faultType().orElseThrow());
        assertEquals("No Signal/Communication", gateway.codeText());
        assertEquals(List.of(), gateway.flags());

        List<VcdsLog.Fault> instruments = log.module("17").orElseThrow().faults();
        assertEquals(List.of("9453074", "9459995"), instruments.stream().map(VcdsLog.Fault::number).toList());
        assertEquals("B103E", instruments.getFirst().code().orElseThrow());
        assertEquals("1B", instruments.getFirst().failureType().orElseThrow());
        assertEquals("00001000", instruments.getFirst().statusBits().orElseThrow());
        assertEquals(List.of("Intermittent", "Confirmed", "Tested Since Memory Clear"), instruments.get(1).flags(),
                "after the [New feature!] note");
        assertEquals("359054 km", instruments.getFirst().freezeFrame("Mileage").orElseThrow());
        VcdsLog.Fault radio = log.module("77").orElseThrow().faults().get(2);
        assertEquals("0022", radio.number());
        assertEquals("U1066", radio.code().orElseThrow());
        assertEquals(List.of("Confirmed", "Tested Since Memory Clear"), radio.flags());

        assertEquals("1K0 909 144 P", log.module("44").orElseThrow().partNumber().orElseThrow(), "44 says 'Part No:'");
        assertEquals("370C00", log.module("17").orElseThrow().coding().orElseThrow());
    }

    @Test
    @DisplayName("the 01 fault-code log parses: the single-module layout, 12 faults with both freeze-frame blocks")
    void freezeFrameLogParses() {
        VcdsLog log = VcdsLog.parse(RealCaptures.freezeFrame01VcdsLog());
        assertTrue(log.vin().isEmpty());
        assertTrue(log.mileageKm().isEmpty());
        assertTrue(log.statusTable().isEmpty());
        assertEquals(1, log.modules().size());
        VcdsLog.Module engine = log.modules().getFirst();
        assertEquals("01", engine.address());
        assertEquals("03L 997 030 M", engine.partNumber().orElseThrow());
        assertEquals("0050078", engine.coding().orElseThrow());
        assertEquals(12, engine.faults().size());
        for (VcdsLog.Fault f : engine.faults()) {
            assertEquals(2, f.freezeFrames().size(), f.number());
            assertEquals(8, f.freezeFrames().getFirst().size(), f.number());
            assertEquals(7, f.freezeFrames().get(1).size(), f.number());
        }
        // Same faults, same order as in the Auto-Scan 7 minutes earlier.
        assertEquals(VcdsLog.parse(RealCaptures.autoScanVcdsLog()).module("01").orElseThrow().faults(), engine.faults());
    }
}
