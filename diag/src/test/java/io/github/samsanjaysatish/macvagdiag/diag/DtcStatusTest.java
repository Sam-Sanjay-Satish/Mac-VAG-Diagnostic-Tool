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
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("KWP (UNCONFIRMED): bits 0..6 alone are symptom code 1<<bit, not intermittent")
    void kwpLowBits(int bit) {
        KwpDtcStatus s = new KwpDtcStatus(1 << bit);
        assertEquals(1 << bit, s.symptomCode());
        assertFalse(s.intermittent());
        assertEquals(List.of(), s.flagNames());
        assertEquals(1 << bit, s.raw());
    }

    @Test
    @DisplayName("KWP (UNCONFIRMED): bit 7 alone is intermittent with symptom 0")
    void kwpBit7() {
        KwpDtcStatus s = new KwpDtcStatus(0x80);
        assertTrue(s.intermittent());
        assertEquals(0, s.symptomCode());
        assertEquals(List.of("intermittent (UNCONFIRMED)"), s.flagNames());
    }

    @Test
    @DisplayName("KWP (UNCONFIRMED): A3 = intermittent 35, 23 = static 35, FF, 00; raw always kept")
    void kwpCombinations() {
        KwpDtcStatus a3 = new KwpDtcStatus(0xA3);
        assertTrue(a3.intermittent());
        assertEquals(35, a3.symptomCode());
        KwpDtcStatus s23 = new KwpDtcStatus(0x23);
        assertFalse(s23.intermittent());
        assertEquals(35, s23.symptomCode());
        assertEquals(127, new KwpDtcStatus(0xFF).symptomCode());
        assertTrue(new KwpDtcStatus(0xFF).intermittent());
        assertEquals(0, new KwpDtcStatus(0x00).symptomCode());
        for (int raw = 0; raw <= 0xFF; raw++) {
            KwpDtcStatus s = new KwpDtcStatus(raw);
            assertEquals(raw, s.symptomCode() | (s.intermittent() ? KwpDtcStatus.INTERMITTENT_BIT : 0), "no bit lost");
        }
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
