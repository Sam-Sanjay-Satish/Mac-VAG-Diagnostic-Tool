package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DTC display formats: SAE J2012 for UDS, 5-digit decimal for KWP (UNCONFIRMED). */
class DtcCodesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "04 01, P0401",
            "03 00, P0300",
            "00 00, P0000",
            "1A 2B, P1A2B",
            "3F FF, P3FFF",
            "44 01, C0401",
            "40 00, C0000",
            "84 01, B0401",
            "80 00, B0000",
            "C4 01, U0401",
            "C1 00, U0100",
            "FF FF, U3FFF",
    })
    @DisplayName("SAE J2012: top 2 bits give P/C/B/U")
    void saeJ2012(String bytes, String expected) {
        byte[] b = Hex.parse(bytes);
        assertEquals(expected, DtcCodes.saeJ2012(Byte.toUnsignedInt(b[0]), Byte.toUnsignedInt(b[1])));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "41 2C, 16684",
            "00 00, 00000",
            "00 01, 00001",
            "04 D2, 01234",
            "FF FF, 65535",
    })
    @DisplayName("KWP code shown as 5-digit decimal of the 16-bit value (UNCONFIRMED)")
    void vagFiveDigit(String bytes, String expected) {
        byte[] b = Hex.parse(bytes);
        assertEquals(expected, DtcCodes.vagFiveDigit((Byte.toUnsignedInt(b[0]) << 8) | Byte.toUnsignedInt(b[1])));
    }

    @Test
    @DisplayName("out-of-range inputs are refused")
    void ranges() {
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.saeJ2012(0x100, 0));
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.saeJ2012(0, -1));
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.vagFiveDigit(0x10000));
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.vagFiveDigit(-1));
    }

    @Test
    @DisplayName("UDS DTC 04 01 00 2F: P0401, failure type 00, record kept raw")
    void udsDtc() {
        UdsDtc dtc = new UdsDtc(0x040100, 0x2F);
        assertEquals("P0401", dtc.displayCode());
        assertEquals(0x00, dtc.failureType());
        assertEquals(RawBytes.ofHex("04 01 00"), dtc.codeBytes());
        assertEquals(RawBytes.ofHex("04 01 00 2F"), dtc.record());
        assertEquals("P0401-00 [04 01 00 2F]", dtc.toString());
        assertEquals("U0100", new UdsDtc(0xC10087, 0x09).displayCode());
        assertEquals(0x87, new UdsDtc(0xC10087, 0x09).failureType());
        assertThrows(IllegalArgumentException.class, () -> new UdsDtc(0x1000000, 0));
    }

    @Test
    @DisplayName("KWP DTC 41 2C A3: 16684, no OBD code without a mapping, record kept raw")
    void kwpDtc() {
        KwpDtc dtc = new KwpDtc(0x412C, 0xA3);
        assertEquals("16684", dtc.displayCode());
        assertTrue(dtc.obdCode().isEmpty());
        assertEquals(RawBytes.ofHex("41 2C"), dtc.codeBytes());
        assertEquals(RawBytes.ofHex("41 2C A3"), dtc.record());
        assertThrows(IllegalArgumentException.class, () -> new KwpDtc(0x10000, 0));
    }

    @Test
    @DisplayName("VAG -> P-code mapping is an empty extension point: NONE maps nothing")
    void vagMappingIsEmpty() {
        for (int code : new int[] {0, 16684, 0x412C, 65535}) {
            assertTrue(VagCodeMapping.NONE.obdCode(code).isEmpty());
        }
    }
}
