package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SlcanCodecTest {

    private static CanFrame decodeFrame(String line) {
        return assertInstanceOf(SlcanMessage.Received.class, SlcanCodec.decode(line)).frame();
    }

    // --- encoding ---

    @Test
    @DisplayName("encode 7E0 [8] 02 10 03 00 00 00 00 00 as t7E080210030000000000\\r")
    void encodeStandardFrame() {
        assertEquals("t7E080210030000000000\r",
                SlcanCodec.encodeFrame(CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00")));
    }

    @Test
    @DisplayName("encode 18DAF110 [2] 3E 00 as T18DAF11023E00\\r")
    void encodeExtendedFrame() {
        assertEquals("T18DAF11023E00\r", SlcanCodec.encodeFrame(CanFrame.extended(0x18DA_F110, 0x3E, 0x00)));
    }

    @Test
    @DisplayName("encode 200 [0] as t2000\\r, small IDs padded: 01A [1] AB as t01A1AB\\r")
    void encodeEmptyAndPadded() {
        assertEquals("t2000\r", SlcanCodec.encodeFrame(CanFrame.standard(0x200)));
        assertEquals("t01A1AB\r", SlcanCodec.encodeFrame(CanFrame.standard(0x01A, 0xAB)));
        assertEquals("T0000001A0\r", SlcanCodec.encodeFrame(CanFrame.extended(0x1A)));
    }

    @Test
    @DisplayName("bitrate commands: S6 is 500 kbit/s, S0 10k, S8 1M")
    void bitrateCommands() {
        assertEquals("S6\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_500));
        assertEquals("S0\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_10));
        assertEquals("S1\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_20));
        assertEquals("S2\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_50));
        assertEquals("S3\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_100));
        assertEquals("S4\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_125));
        assertEquals("S5\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_250));
        assertEquals("S8\r", SlcanCodec.bitrateCommand(Bitrate.KBIT_1000));
    }

    @Test
    void commandConstants() {
        assertEquals("O\r", SlcanCodec.OPEN_COMMAND);
        assertEquals("C\r", SlcanCodec.CLOSE_COMMAND);
        assertEquals("M1\r", SlcanCodec.SILENT_MODE_COMMAND);
    }

    // --- decoding hand-written lines ---

    @Test
    @DisplayName("decode t7E880641000000000000 as 7E8 [8] 06 41 00 00 00 00 00 00")
    void decodeStandardFrame() {
        assertEquals(CanFrame.standardHex(0x7E8, "06 41 00 00 00 00 00 00"), decodeFrame("t7E880641000000000000"));
    }

    @Test
    @DisplayName("decode T18DAF11023E00 as extended 18DAF110 [2] 3E 00")
    void decodeExtendedFrame() {
        assertEquals(CanFrame.extended(0x18DA_F110, 0x3E, 0x00), decodeFrame("T18DAF11023E00"));
    }

    @Test
    @DisplayName("decode t2000 as 200 [0]")
    void decodeEmptyFrame() {
        assertEquals(CanFrame.standard(0x200), decodeFrame("t2000"));
    }

    @Test
    @DisplayName("lowercase and uppercase hex decode the same: t7e8 2 ab cd")
    void decodeLowercaseHex() {
        assertEquals(CanFrame.standard(0x7E8, 0xAB, 0xCD), decodeFrame("t7e82abcd"));
        assertEquals(CanFrame.standard(0x7E8, 0xAB, 0xCD), decodeFrame("t7E82ABCD"));
        assertEquals(CanFrame.extended(0x18DA_F110, 0xEF), decodeFrame("T18daf1101ef"));
    }

    @Test
    @DisplayName("decode remote frames r7E08 and R18DAF1100")
    void decodeRemoteFrames() {
        assertEquals(new SlcanMessage.RemoteFrame(0x7E0, false, 8), SlcanCodec.decode("r7E08"));
        assertEquals(new SlcanMessage.RemoteFrame(0x18DA_F110, true, 0), SlcanCodec.decode("R18DAF1100"));
    }

    @Test
    @DisplayName("empty line is OK, \\a is an error reply, z/Z are transmit acks")
    void decodeRepliesAndErrors() {
        assertInstanceOf(SlcanMessage.Ack.class, SlcanCodec.decode(""));
        assertInstanceOf(SlcanMessage.Error.class, SlcanCodec.decode("\u0007"));
        assertInstanceOf(SlcanMessage.Ack.class, SlcanCodec.decode("z"));
        assertInstanceOf(SlcanMessage.Ack.class, SlcanCodec.decode("Z"));
    }

    @Test
    void roundTrips() {
        List<CanFrame> frames = List.of(
                CanFrame.standardHex(0x200, "01 C0 00 10 00 03 01"),
                CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00"),
                CanFrame.standard(0x000),
                CanFrame.standard(0x7FF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
                CanFrame.extended(0x1FFF_FFFF, 0x00),
                CanFrame.extended(0x0));
        for (CanFrame frame : frames) {
            String line = SlcanCodec.encodeFrame(frame);
            assertTrue(line.endsWith("\r"));
            assertEquals(frame, decodeFrame(line.substring(0, line.length() - 1)), line);
        }
    }

    // --- malformed lines ---

    private static void assertRejected(String line, String expectedFragment) {
        SlcanFormatException e = assertThrows(SlcanFormatException.class, () -> SlcanCodec.decode(line));
        assertTrue(e.getMessage().contains(expectedFragment),
                "message should contain '" + expectedFragment + "': " + e.getMessage());
        assertTrue(e.getMessage().contains("Malformed slcan line"), e.getMessage());
    }

    @Test
    @DisplayName("t7E08021003 (8 declared, 3 given) is rejected: wrong length")
    void wrongLengthTooShort() {
        assertRejected("t7E08021003", "expected 21 characters for 8 data bytes, got 11");
    }

    @Test
    @DisplayName("t7E0102AB (1 declared, 2 given) is rejected: wrong length")
    void wrongLengthTooLong() {
        assertRejected("t7E0102AB", "expected 7 characters");
    }

    @Test
    void tooShortForId() {
        assertRejected("t7E", "too short");
        assertRejected("T18DAF1", "too short");
        assertRejected("t", "too short");
    }

    @Test
    @DisplayName("t7E02GG00 is rejected: bad hex")
    void badHexInData() {
        assertRejected("t7E02GG00", "bad hex digit 'G' at position 5");
    }

    @Test
    void badHexInIdAndLength() {
        assertRejected("t7X01AB", "bad hex digit 'X'");
        assertRejected("t7E0", "too short");
        assertRejected("t7E0X", "bad hex digit 'X'");
    }

    @Test
    @DisplayName("t7E09... is rejected: length 9 exceeds 8")
    void lengthNine() {
        assertRejected("t7E09000000000000000000", "length 9 exceeds 8");
    }

    @Test
    @DisplayName("t8001AB is rejected: standard ID 800 out of range")
    void standardIdOutOfRange() {
        assertRejected("t8001AB", "0x800 out of range");
    }

    @Test
    @DisplayName("T200000001AB is rejected: extended ID 20000000 out of range")
    void extendedIdOutOfRange() {
        assertRejected("T200000001AB", "0x20000000 out of range");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x123", "V1234", "1234", "?", "Fhello"})
    void unknownPrefix(String line) {
        assertRejected(line, "unknown prefix");
    }

    @Test
    void canFdRejected() {
        assertRejected("d7E0811223344556677889900", "CAN FD");
        assertRejected("b7E0F00", "CAN FD");
    }

    @Test
    void controlCharactersArePrintableInErrors() {
        assertRejected("\u0001abc", "\\x01");
    }

    @Test
    void timestampSuffixIsRejectedSinceZ1IsNeverEnabled() {
        // Lawicel Z1 appends 4 hex digits (ms); the CANable stock firmware doesn't support it and we never enable it.
        assertRejected("t7E0102EA5F", "expected 7 characters");
    }
}
