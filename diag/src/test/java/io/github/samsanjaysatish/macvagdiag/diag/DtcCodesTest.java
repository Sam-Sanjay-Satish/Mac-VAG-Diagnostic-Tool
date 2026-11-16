package io.github.samsanjaysatish.macvagdiag.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DTC display formats: SAE J2012 for UDS, VAG decimal numbers for KWP and UDS, the VAG to P-code mapping. */
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
            "05 18, 01304",
            "08 1E, 02078",
    })
    @DisplayName("KWP code shown as 5-digit decimal of the 16-bit value (gateway 05 18 = 01304 in the VCDS log)")
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
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.vagSixDigit(0x10000));
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.vagUdsNumber(0x1000000));
        assertThrows(IllegalArgumentException.class, () -> DtcCodes.vagUdsNumber(-1));
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
    @DisplayName("NONE maps nothing")
    void noneMapsNothing() {
        for (int code : new int[] {0, 0x0518, 0x2BA6, 0x412C, 65535}) {
            assertTrue(VagCodeMapping.NONE.obdCode(code).isEmpty());
        }
    }

    @ParameterizedTest(name = "{0} -> {1} / {2}")
    @CsvSource({
            "2B A6, 011174, P2BA6",
            "1A 58, 006744, P1A58",
            "19 46, 006470, P1946",
            "04 01, 001025, P0401",
            "24 0F, 009231, P240F",
            "06 91, 001681, P0691",
            "05 44, 001348, P0544",
            "00 87, 000135, P0087",
    })
    @DisplayName("P_CODE_HEX: all 8 distinct engine codes of the 2026-11-13 VCDS log, VAG number and P-code")
    void engineCodePairs(String bytes, String vcdsNumber, String pCode) {
        byte[] b = Hex.parse(bytes);
        int code = (Byte.toUnsignedInt(b[0]) << 8) | Byte.toUnsignedInt(b[1]);
        assertEquals(java.util.Optional.of(pCode), VagCodeMapping.P_CODE_HEX.obdCode(code));
        KwpDtc dtc = new KwpDtc(code, new KwpDtcStatus(0x20), VagCodeMapping.P_CODE_HEX.obdCode(code));
        assertEquals(vcdsNumber, dtc.displayCode(), "6 digits when there is a P-code");
        assertEquals(vcdsNumber, DtcCodes.vagSixDigit(code));
    }

    @Test
    @DisplayName("P_CODE_HEX: 3F FF is P3FFF, 40 00 and up stay unmapped (older offset scheme, UNCONFIRMED)")
    void pCodeLimit() {
        assertEquals(java.util.Optional.of("P3FFF"), VagCodeMapping.P_CODE_HEX.obdCode(0x3FFF));
        assertEquals(java.util.Optional.of("P0000"), VagCodeMapping.P_CODE_HEX.obdCode(0));
        for (int code : new int[] {0x4000, 0x412C, 0xFFFF, -1}) {
            assertTrue(VagCodeMapping.P_CODE_HEX.obdCode(code).isEmpty(), Integer.toHexString(code));
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "90 3E 12, 9453074",
            "90 59 1B, 9459995",
            "90 4F 13, 9457427",
            "90 4E 13, 9457171",
            "00 00 16, 0022",
    })
    @DisplayName("UDS VAG number: decimal of the 24 bits, every UDS code of the 2026-11-13 VCDS log")
    void udsNumbers(String bytes, String expected) {
        byte[] b = Hex.parse(bytes);
        int code = (Byte.toUnsignedInt(b[0]) << 16) | (Byte.toUnsignedInt(b[1]) << 8) | Byte.toUnsignedInt(b[2]);
        assertEquals(expected, new UdsDtc(code, 0x08).vagNumber());
    }
}
