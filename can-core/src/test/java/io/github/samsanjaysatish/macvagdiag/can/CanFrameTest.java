package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanFrameTest {

    @Test
    @DisplayName("standard ID 7FF is accepted, 800 is rejected")
    void standardIdBounds() {
        assertEquals(0x7FF, CanFrame.standard(0x7FF).id());
        assertEquals(0x000, CanFrame.standard(0x000).id());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CanFrame.standard(0x800));
        assertTrue(e.getMessage().contains("0x800"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CanFrame.standard(-1));
    }

    @Test
    @DisplayName("extended ID 1FFFFFFF is accepted, 20000000 is rejected")
    void extendedIdBounds() {
        CanFrame max = CanFrame.extended(0x1FFF_FFFF);
        assertEquals(0x1FFF_FFFF, max.id());
        assertTrue(max.isExtended());
        assertEquals(0x800, CanFrame.extended(0x800).id(), "an extended frame may use a small ID");
        assertThrows(IllegalArgumentException.class, () -> CanFrame.extended(0x2000_0000));
        assertThrows(IllegalArgumentException.class, () -> CanFrame.extended(-1));
    }

    @Test
    @DisplayName("length 0 and 8 are accepted, 9 is rejected")
    void lengthBounds() {
        assertEquals(0, CanFrame.standard(0x7E0).length());
        assertEquals(8, CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00").length());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CanFrame(0x7E0, false, new byte[9]));
        assertTrue(e.getMessage().contains("9"), e.getMessage());
    }

    @Test
    void byteValuesOutOfRangeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanFrame.standard(0x7E0, 0x100));
        assertThrows(IllegalArgumentException.class, () -> CanFrame.standard(0x7E0, -1));
    }

    @Test
    @DisplayName("frames with the same content are equal: 7E0 [3] 02 10 03")
    void equalityOnContent() {
        CanFrame a = CanFrame.standard(0x7E0, 0x02, 0x10, 0x03);
        CanFrame b = new CanFrame(0x7E0, false, new byte[] {0x02, 0x10, 0x03});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, CanFrame.standard(0x7E0, 0x02, 0x10, 0x01), "different data");
        assertNotEquals(a, CanFrame.standard(0x7E8, 0x02, 0x10, 0x03), "different ID");
        assertNotEquals(a, CanFrame.extended(0x7E0, 0x02, 0x10, 0x03), "different ID type");
        assertNotEquals(a, CanFrame.standard(0x7E0, 0x02, 0x10), "different length");
    }

    @Test
    void dataIsCopiedIn() {
        byte[] data = {0x02, 0x10, 0x03};
        CanFrame frame = new CanFrame(0x7E0, false, data);
        data[0] = 0x55;
        assertEquals(0x02, frame.dataByte(0));
    }

    @Test
    void dataIsCopiedOut() {
        CanFrame frame = CanFrame.standard(0x7E0, 0x02, 0x10, 0x03);
        byte[] out = frame.data();
        out[0] = 0x55;
        assertArrayEquals(new byte[] {0x02, 0x10, 0x03}, frame.data());
        assertFalse(out == frame.data(), "each call returns a fresh copy");
    }

    @Test
    void dataByteIsUnsigned() {
        assertEquals(0xFF, CanFrame.standard(0x7E8, 0xFF).dataByte(0));
    }

    @Test
    @DisplayName("toString is bus hex: 7E0 [8] 02 10 03 00 00 00 00 00")
    void toStringStandard() {
        assertEquals("7E0 [8] 02 10 03 00 00 00 00 00",
                CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00").toString());
        assertEquals("01A [1] 0F", CanFrame.standard(0x01A, 0x0F).toString(), "ID padded to 3 digits");
    }

    @Test
    @DisplayName("toString for extended frames uses 8 ID digits: 18DAF110 [2] 3E 00")
    void toStringExtended() {
        assertEquals("18DAF110 [2] 3E 00", CanFrame.extended(0x18DA_F110, 0x3E, 0x00).toString());
    }

    @Test
    @DisplayName("toString for an empty frame: 200 [0]")
    void toStringEmpty() {
        assertEquals("200 [0]", CanFrame.standard(0x200).toString());
    }

    @Test
    void hexHelpers() {
        assertArrayEquals(new byte[] {0x02, 0x10, (byte) 0xAB}, Hex.parse("02 10 ab"));
        assertArrayEquals(new byte[] {0x02, 0x10, (byte) 0xAB}, Hex.parse("0210AB"));
        assertEquals("02 10 AB", Hex.format(new byte[] {0x02, 0x10, (byte) 0xAB}));
        assertEquals("0210AB", Hex.formatCompact(new byte[] {0x02, 0x10, (byte) 0xAB}));
        assertThrows(IllegalArgumentException.class, () -> Hex.parse("021"));
        assertThrows(IllegalArgumentException.class, () -> Hex.parse("0G"));
    }

    @Test
    void bitrateLookup() {
        assertEquals(Bitrate.KBIT_500, Bitrate.ofBitsPerSecond(500_000));
        assertThrows(IllegalArgumentException.class, () -> Bitrate.ofBitsPerSecond(33_333));
    }
}
