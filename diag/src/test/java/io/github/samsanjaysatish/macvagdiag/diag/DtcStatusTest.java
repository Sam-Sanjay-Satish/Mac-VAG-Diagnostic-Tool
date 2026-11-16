package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Status byte decoding: every bit on its own, then combinations; the raw byte is always kept. */
class DtcStatusTest {

    private static final List<String> UDS_NAMES = List.of(
            "testFailed", "testFailedThisOperationCycle", "pendingDTC", "confirmedDTC",
            "testNotCompletedSinceLastClear", "testFailedSinceLastClear", "testNotCompletedThisOperationCycle",
            "warningIndicatorRequested");

    @ParameterizedTest(name = "UDS status bit {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("UDS: each bit alone sets exactly its ISO 14229 flag")
    void udsEachBit(int bit) {
        UdsDtcStatus s = new UdsDtcStatus(1 << bit);
        UdsDtcStatus.Flag expected = UdsDtcStatus.Flag.values()[bit];
        assertEquals(1 << bit, expected.mask());
        assertEquals(Set.of(expected), s.flags());
        assertEquals(List.of(UDS_NAMES.get(bit)), s.flagNames());
        assertEquals(1 << bit, s.raw());
        for (UdsDtcStatus.Flag f : UdsDtcStatus.Flag.values()) {
            assertEquals(f == expected, s.has(f), f.name());
        }
    }

    @Test
    @DisplayName("UDS: 00 none, FF all, 2F typical confirmed fault, 09, 24, 50")
    void udsCombinations() {
        assertEquals(Set.of(), new UdsDtcStatus(0x00).flags());
        assertEquals(EnumSet.allOf(UdsDtcStatus.Flag.class), new UdsDtcStatus(0xFF).flags());
        assertEquals(UDS_NAMES, new UdsDtcStatus(0xFF).flagNames());
        assertEquals(EnumSet.of(UdsDtcStatus.Flag.TEST_FAILED, UdsDtcStatus.Flag.TEST_FAILED_THIS_OPERATION_CYCLE,
                UdsDtcStatus.Flag.PENDING_DTC, UdsDtcStatus.Flag.CONFIRMED_DTC, UdsDtcStatus.Flag.TEST_FAILED_SINCE_LAST_CLEAR),
                new UdsDtcStatus(0x2F).flags());
        assertEquals(EnumSet.of(UdsDtcStatus.Flag.TEST_FAILED, UdsDtcStatus.Flag.CONFIRMED_DTC), new UdsDtcStatus(0x09).flags());
        assertEquals(EnumSet.of(UdsDtcStatus.Flag.PENDING_DTC, UdsDtcStatus.Flag.TEST_FAILED_SINCE_LAST_CLEAR),
                new UdsDtcStatus(0x24).flags());
        assertEquals(EnumSet.of(UdsDtcStatus.Flag.TEST_NOT_COMPLETED_SINCE_LAST_CLEAR,
                UdsDtcStatus.Flag.TEST_NOT_COMPLETED_THIS_OPERATION_CYCLE), new UdsDtcStatus(0x50).flags());
        assertEquals("2F [testFailed, testFailedThisOperationCycle, pendingDTC, confirmedDTC, testFailedSinceLastClear]",
                new UdsDtcStatus(0x2F).toString());
    }

    @Test
    @DisplayName("UDS: every value 00..FF round-trips: flags' masks OR back to the raw byte")
    void udsRoundTrip() {
        for (int raw = 0; raw <= 0xFF; raw++) {
            int rebuilt = 0;
            for (UdsDtcStatus.Flag f : new UdsDtcStatus(raw).flags()) {
                rebuilt |= f.mask();
            }
            assertEquals(raw, rebuilt);
        }
    }

    @ParameterizedTest(name = "KWP status bit {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("KWP: bits 0..4 alone are fault type 1<<bit, intermittent (bit 6 clear), no MIL")
    void kwpLowBits(int bit) {
        KwpDtcStatus s = new KwpDtcStatus(1 << bit);
        assertEquals(1 << bit, s.faultType());
        assertTrue(s.intermittent());
        assertFalse(s.milOn());
        assertEquals(List.of("Intermittent"), s.flagNames());
        assertEquals(1 << bit, s.raw());
    }

    @Test
    @DisplayName("KWP: bit 5 alone (UNCONFIRMED meaning) changes nothing decoded; bit 6 alone = not intermittent; bit 7 = MIL ON")
    void kwpHighBits() {
        KwpDtcStatus b5 = new KwpDtcStatus(0x20);
        assertEquals(0, b5.faultType());
        assertTrue(b5.intermittent());
        assertEquals(List.of("Intermittent"), b5.flagNames());
        KwpDtcStatus b6 = new KwpDtcStatus(0x40);
        assertFalse(b6.intermittent());
        assertFalse(b6.milOn());
        assertEquals(List.of(), b6.flagNames());
        KwpDtcStatus b7 = new KwpDtcStatus(0x80);
        assertTrue(b7.milOn());
        assertTrue(b7.intermittent());
        assertEquals(List.of("Intermittent", "MIL ON"), b7.flagNames());
    }

    @ParameterizedTest(name = "KWP {0} -> {1}, type {2}, {3}")
    @DisplayName("KWP: every status byte of the 2026-11-13 capture against the VCDS log")
    @CsvSource(delimiter = '|', value = {
        "E0 | 11100000 | 000 | MIL ON",
        "60 | 01100000 | 000 | ",
        "20 | 00100000 | 000 | Intermittent",
        "61 | 01100001 | 001 | ",
        "22 | 00100010 | 002 | Intermittent",
        "21 | 00100001 | 001 | Intermittent",
        "62 | 01100010 | 002 | ",
        "26 | 00100110 | 006 | Intermittent",
        "64 | 01100100 | 004 | ",
    })
    void kwpCaptured(String hex, String bits, String type, String words) {
        KwpDtcStatus s = new KwpDtcStatus(Integer.parseInt(hex, 16));
        assertEquals(bits, s.bits());
        assertEquals(type, s.faultTypeText());
        assertEquals(words == null ? List.of() : List.of(words), s.flagNames());
    }

    @Test
    @DisplayName("KWP: every value 00..FF keeps its raw byte; type, bit 5, bit 6, bit 7 rebuild it")
    void kwpRoundTrip() {
        for (int raw = 0; raw <= 0xFF; raw++) {
            KwpDtcStatus s = new KwpDtcStatus(raw);
            int rebuilt = s.faultType() | (raw & KwpDtcStatus.UNKNOWN_BIT_5)
                    | (s.intermittent() ? 0 : KwpDtcStatus.NOT_INTERMITTENT_BIT) | (s.milOn() ? KwpDtcStatus.MIL_ON_BIT : 0);
            assertEquals(raw, rebuilt, "no bit lost");
            assertEquals(raw, Integer.parseInt(s.bits(), 2));
        }
        assertEquals("E0 (11100000, type 000, MIL ON)", new KwpDtcStatus(0xE0).toString());
        assertEquals("61 (01100001, type 001)", new KwpDtcStatus(0x61).toString());
    }

    @ParameterizedTest(name = "UDS {0} -> {1}")
    @DisplayName("UDS VCDS wording: 08 and 09 from the 2026-11-13 VCDS log, plus the words' bits on their own")
    @CsvSource(delimiter = '|', value = {
        "08 | Intermittent - Confirmed - Tested Since Memory Clear",
        "09 | Confirmed - Tested Since Memory Clear",
        "01 | Tested Since Memory Clear",
        "11 | ",
        "10 | Intermittent",
        "00 | Intermittent - Tested Since Memory Clear",
    })
    void udsVcdsWording(String hex, String text) {
        assertEquals(text == null ? "" : text, new UdsDtcStatus(Integer.parseInt(hex, 16)).vcdsText());
    }

    @Test
    @DisplayName("status must be a byte")
    void ranges() {
        assertThrows(IllegalArgumentException.class, () -> new UdsDtcStatus(0x100));
        assertThrows(IllegalArgumentException.class, () -> new UdsDtcStatus(-1));
        assertThrows(IllegalArgumentException.class, () -> new KwpDtcStatus(0x100));
        assertThrows(IllegalArgumentException.class, () -> new KwpDtcStatus(-1));
    }
}
