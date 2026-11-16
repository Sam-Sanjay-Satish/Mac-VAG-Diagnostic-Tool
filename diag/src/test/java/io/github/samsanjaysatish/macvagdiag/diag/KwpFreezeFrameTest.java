package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.transport.FakeDiagnosticChannel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** KWP {@code 12 00 04 [hi] [lo]} edge cases and the measuring-value formulas; real records are in {@link KwpCaptureTest}. */
class KwpFreezeFrameTest {

    private static final KwpDtc P2BA6 = new KwpDtc(0x2BA6, 0xE0);
    private static final String GOOD = CaptureBytes.ENGINE_FREEZE_FRAMES.getFirst();

    private final FakeTimeSource time = new FakeTimeSource();

    private DiagResult<FreezeFrame> read(KwpDtc dtc, String... responses) throws InterruptedException {
        FakeDiagnosticChannel ch = FakeDiagnosticChannel.kwp().on("12 00 04 " + dtc.codeBytes().hex(), responses);
        DiagResult<FreezeFrame> r = new KwpDiagnosticService(ch, time).readFreezeFrame(dtc);
        assertEquals(List.of("12 00 04 " + dtc.codeBytes().hex()), ch.requestsHex());
        return r;
    }

    private static FreezeFrame ok(DiagResult<FreezeFrame> r) {
        assertTrue(r.isSuccess(), r.message());
        return r.valueIfSuccess().orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("responses not in the 52 00 6C 01 ... 04 hi lo layout: whole response kept raw with a note, no fields")
    @CsvSource({
        "too short, 52 00 6C 01 E0 02 FE FF 05 87 10 00 01 FF BE 93 5E 04 2B",
        "partial triplet, 52 00 6C 01 E0 02 FE FF 05 87 10 00 01 FF BE 93 5E 01 73 04 2B A6",
        "frame number 01, 52 01 6C 01 E0 02 FE FF 05 87 10 00 01 FF BE 93 5E 04 2B A6",
        "no 04 trailer, 52 00 6C 01 E0 02 FE FF 05 87 10 00 01 FF BE 93 5E 05 2B A6",
    })
    void unexpectedLayout(String what, String response) throws InterruptedException {
        FreezeFrame ff = ok(read(P2BA6, response));
        assertEquals(List.of(new RawRecord("12 00 04 2B A6 (whole response, unexpected layout)", RawBytes.ofHex(response))),
                ff.records());
        assertEquals(List.of(), ff.fields());
        assertEquals(1, ff.notes().size());
        assertEquals(List.of(RawBytes.ofHex(response)), ff.responses());
    }

    @Test
    @DisplayName("answer for another code, another status, other header bytes: decoded, each noted")
    void mismatchesNoted() throws InterruptedException {
        String other = GOOD.replace("52 00 6C 01 E0", "52 00 6D 02 60").replace("04 2B A6", "04 1A 58");
        FreezeFrame ff = ok(read(P2BA6, other));
        assertEquals(3, ff.notes().size(), ff.notes().toString());
        assertTrue(ff.notes().get(0).contains("header bytes 6D 02"));
        assertTrue(ff.notes().get(1).contains("answered for code 1A58, not 2BA6"));
        assertTrue(ff.notes().get(2).contains("stored status 60 differs from the DTC list entry's E0"));
        assertFalse(ff.fields().isEmpty());
    }

    @Test
    @DisplayName("time bits that aren't a time of day (hour 31): noted, no TIME field, the rest decoded")
    void badTime() throws InterruptedException {
        FreezeFrame ff = ok(read(P2BA6, GOOD.replace("01 FF BE 93 5E", "01 FF BF FF FF")));
        assertTrue(ff.fields().stream().noneMatch(f -> f.kind() == FreezeFrame.Field.Kind.TIME));
        assertEquals(1, ff.notes().size());
        assertTrue(ff.notes().getFirst().contains("time bits 1FFFF"), ff.notes().getFirst());
    }

    @Test
    @DisplayName("VCDS's +1 second carries over: 09:13:59 -> 09:14:00, 23:59:59 -> 00:00:00")
    void timeCarry() {
        assertEquals(Optional.of("09:14:00"), KwpFreezeFrames.vcdsTime((9 << 12) | (13 << 6) | 59));
        assertEquals(Optional.of("00:00:00"), KwpFreezeFrames.vcdsTime((23 << 12) | (59 << 6) | 59));
        assertEquals(Optional.of("09:13:31"), KwpFreezeFrames.vcdsTime(0x0935E));
        assertEquals(Optional.empty(), KwpFreezeFrames.vcdsTime((12 << 12) | (60 << 6)));
    }

    @Test
    @DisplayName("12 00 04 -> 7F 12 31: Negative; 7F 12 21: Busy; 7F 12 78 then the answer: decoded")
    void negatives() throws InterruptedException {
        assertInstanceOf(DiagResult.Negative.class, read(P2BA6, "7F 12 31"));
        assertInstanceOf(DiagResult.Busy.class, read(P2BA6, "7F 12 21"));
        assertTrue(read(P2BA6, "7F 12 78", GOOD).isSuccess());
    }

    @ParameterizedTest(name = "formula {0}: a={1} b={2} -> {3}")
    @DisplayName("measuring-value formulas, values from the VCDS log, plus unknown and undefined ones kept raw")
    @CsvSource(delimiter = '|', value = {
        "01 | 73 | 24 | RPM: 828 /min",
        "05 | 09 | 73 | Temperature: 13.5°C",
        "05 | 01 | 32 | Temperature: -5.0°C",
        "06 | 4C | B6 | Voltage: 13.83 V",
        "07 | 64 | 4B | Speed: 75.0 km/h",
        "08 | 0A | 05 | (no units): 5.0",
        "13 | 1C | 0A | Tank Cont.: 2.8 l",
        "14 | 65 | 8C | Lambda: 9.5 %",
        "21 | FF | B7 | Load: 71.8 %",
        "21 | 00 | 10 | formula 21: 00 10 (not decoded)",
        "27 | 46 | 9C | Inj. Quantity: 42.8 mg/str",
        "31 | C8 | 34 | Mass Air / Rev.: 260.0 mg/str",
        "60 | 79 | 53 | Pressure: 1004.3 mbar",
        "64 | 50 | AB | Pressure: 1368.0 bar",
        "99 | 12 | 34 | formula 99: 12 34 (not decoded)",
    })
    void formulas(String formula, String a, String b, String expected) {
        assertEquals(expected, KwpMeasuringValue.describe(Integer.parseInt(formula, 16), Integer.parseInt(a, 16),
                Integer.parseInt(b, 16)));
        assertEquals(!formula.equals("99"), KwpMeasuringValue.known(Integer.parseInt(formula, 16)));
    }
}
