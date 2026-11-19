package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * KWP measuring blocks, {@code 21 gg} → {@code 61 gg} + triplets. The responses are the engine's, from the
 * 2026-11-18 capture (listen-only, VCDS polling), and the expected values are VCDS's CSV for the same groups.
 */
class MeasuringBlockTest {

    // Engine (01), 2026-11-18, idle.
    static final String GROUP_001 = "61 01 01 73 24 27 46 16 64 50 29 05 09 B3 25 02 7A 25 00 00 25 01 7D 25 00 00";
    static final String GROUP_007 = "61 07 05 09 8F 25 00 00 05 09 83 05 09 AF 25 00 00 25 00 00 25 00 00 25 00 00";
    static final String GROUP_011 = "61 0B 01 73 24 60 79 53 60 79 52 21 FF 25 08 0A 00 05 09 6C 10 FF 00 15 4B 00";
    static final String GROUP_099 = "61 63 01 73 24 05 3C 7F 05 3C 79 05 3C 78 30 00 00 25 00 00 30 00 00 30 00 00";
    static final String GROUP_100 = "61 64 05 3C 78 05 3C 74 32 27 82 32 27 80 30 00 00 30 00 00 30 00 00 30 00 00";

    private static MeasuringBlock parse(int group, String hex) {
        return MeasuringBlock.parse(group, RawBytes.ofHex(hex)).valueIfSuccess()
                .orElseThrow(() -> new AssertionError("did not parse: " + hex));
    }

    private static List<String> texts(MeasuringBlock b) {
        return b.fields().stream().map(MeasuringValue::text).toList();
    }

    @Test
    @DisplayName("real engine groups decode to VCDS's CSV values (001, 007, 099, 100)")
    void realGroups() {
        assertEquals(List.of("828 /min", "6.0 mg/str", "328.0 bar", "71.1 °C"), texts(parse(1, GROUP_001)));
        assertEquals(List.of("38.7 °C", "(empty)", "27.9 °C", "67.5 °C"), texts(parse(7, GROUP_007)));
        assertEquals(List.of("828 /min", "162.0 °C", "126.0 °C", "120.0 °C"), texts(parse(99, GROUP_099)));
        assertEquals(List.of("120.0 °C", "96.0 °C", "5 mbar", "0 mbar"), texts(parse(100, GROUP_100)));
        // 011: RPM and field 2 match VCDS's CSV; fields 3 and 4 moved between VCDS's sample and this one
        // (VCDS: 1004.3 mbar, 100.0 %), so they are checked against the formulas only.
        assertEquals(List.of("828 /min", "1004.3 mbar", "992.2 mbar", "14.5 %"), texts(parse(11, GROUP_011)));
    }

    @Test
    @DisplayName("only triplets 1-4 are fields; 5-8 are kept raw")
    void fieldsAndExtras() {
        MeasuringBlock b = parse(1, GROUP_001);
        assertEquals(4, b.fields().size());
        assertEquals(List.of("25 02 7A", "25 00 00", "25 01 7D", "25 00 00"), b.extraTriplets());
        assertEquals(RawBytes.ofHex(GROUP_001), b.response());
        MeasuringValue rpm = b.field(1).orElseThrow();
        assertEquals("01 73 24", rpm.raw());
        assertEquals("RPM", rpm.quantity());
        assertEquals("/min", rpm.unit());
        assertEquals("828", rpm.value().orElseThrow().toPlainString());
        assertTrue(b.field(5).isEmpty());
    }

    @Test
    @DisplayName("25 00 00 is an empty field (007 field 2, oil temperature, not measured by this engine)")
    void emptyField() {
        MeasuringValue oil = parse(7, GROUP_007).field(2).orElseThrow();
        assertTrue(oil.empty());
        assertFalse(oil.decoded());
        assertEquals("", oil.unit());
        assertEquals("", oil.quantity());
    }

    @Test
    @DisplayName("unknown formula (hand-written, shaped like group 033's 1F 50 20): kept raw, no value, no unit")
    void unknownFormula() {
        MeasuringBlock b = parse(33, "61 21 01 73 24 05 3C 7E 1F 50 20 1F 50 20");
        MeasuringValue f3 = b.field(3).orElseThrow();
        assertFalse(f3.decoded());
        assertFalse(f3.empty());
        assertEquals("formula 1F: 50 20 (not decoded)", f3.text());
        assertEquals("", f3.unit());
    }

    @Test
    @DisplayName("formula 21 with a = 0 has no value (no division by zero)")
    void loadWithZeroDivisor() {
        MeasuringValue v = parse(5, "61 05 21 00 37").field(1).orElseThrow();
        assertFalse(v.decoded());
        assertEquals("formula 21: 00 37 (not decoded)", v.text());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "61 08 05 09 8F, wrong group echo",
            "61, too short",
            "61 07, no triplets",
            "61 07 05 09, partial triplet",
            "61 07 05 09 8F 25 00, partial second triplet"})
    void malformed(String hex, String why) {
        DiagResult<MeasuringBlock> r = MeasuringBlock.parse(7, RawBytes.ofHex(hex));
        if (!(r instanceof DiagResult.Malformed<MeasuringBlock> m)) {
            throw new AssertionError(why + ": expected Malformed, got " + r);
        }
        assertEquals(RawBytes.ofHex(hex), m.response());
    }

    @Test
    @DisplayName("service: one 21 gg per call, negative and pending answers handled like every other read")
    void overTheChannel() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp()
                .on("21 07", GROUP_007)
                .on("21 63", "7F 21 78", GROUP_099)
                .on("21 2A", "7F 21 31");
        KwpDiagnosticService k = new KwpDiagnosticService(ch, FakeTimeSource.autoAdvancing());
        assertEquals("38.7 °C", k.readMeasuringBlock(7).valueIfSuccess().orElseThrow().fields().getFirst().text());
        assertEquals(99, k.readMeasuringBlock(99).valueIfSuccess().orElseThrow().group());
        if (!(k.readMeasuringBlock(42) instanceof DiagResult.Negative<MeasuringBlock> n)) {
            throw new AssertionError("expected Negative");
        }
        assertEquals(0x31, n.response().code());
        assertEquals(List.of("21 07", "21 63", "21 2A"), ch.requestsHex());
        assertThrows(IllegalArgumentException.class, () -> k.readMeasuringBlock(0));
        assertThrows(IllegalArgumentException.class, () -> k.readMeasuringBlock(256));
        assertEquals(3, ch.requestsHex().size(), "nothing sent for a bad group");
        assertEquals(1, KwpDiagnosticService.MEASURING_BLOCK_REQUESTS);
    }

    @Test
    @DisplayName("UDS service: NotAvailable, nothing sent")
    void udsHasNoGroups() throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.uds();
        UdsDiagnosticService u = new UdsDiagnosticService(ch, FakeTimeSource.autoAdvancing());
        assertInstanceOf(DiagResult.NotAvailable.class, u.readMeasuringBlock(7));
        assertEquals(List.of(), ch.requestsHex());
        assertThrows(IllegalArgumentException.class, () -> u.readMeasuringBlock(0));
    }
}
