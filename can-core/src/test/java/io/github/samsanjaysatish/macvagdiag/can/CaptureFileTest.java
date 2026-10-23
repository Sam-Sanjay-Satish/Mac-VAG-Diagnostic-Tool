package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaptureFileTest {

    private static final Instant T0 = Instant.ofEpochSecond(1_729_600_000L, 123_456_000);

    private static Capture read(String text) throws IOException {
        return CaptureReader.read(new StringReader(text));
    }

    @Test
    @DisplayName("writes 7E0 [8] 02 10 03 ... as (1729600000.123456) can0 7E0#0210030000000000")
    void writesCandumpLine() throws IOException {
        StringWriter out = new StringWriter();
        try (CaptureWriter writer = new CaptureWriter(out)) {
            writer.write(T0, CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00"));
        }
        assertEquals("(1729600000.123456) can0 7E0#0210030000000000\n", out.toString());
    }

    @Test
    void writesExtendedEmptyAndPaddedTimestamps() throws IOException {
        StringWriter out = new StringWriter();
        CaptureWriter writer = new CaptureWriter(out, "slcan0");
        writer.write(Instant.ofEpochSecond(5, 1_000), CanFrame.extended(0x18DA_F110, 0x3E, 0x00));
        writer.write(Instant.ofEpochSecond(5, 999_999_999), CanFrame.standard(0x01A));
        writer.flush();
        assertEquals("""
                (5.000001) slcan0 18DAF110#3E00
                (5.999999) slcan0 01A#
                """, out.toString());
    }

    @Test
    void writesMetadataAndComments() throws IOException {
        StringWriter out = new StringWriter();
        CaptureWriter writer = new CaptureWriter(out);
        writer.writeMetadata("bitrate", "500000");
        writer.writeMetadata("car", "2013 VW Jetta TDI");
        writer.writeComment("VCDS auto-scan, engine off");
        writer.flush();
        assertEquals("""
                # bitrate: 500000
                # car: 2013 VW Jetta TDI
                # VCDS auto-scan, engine off
                """, out.toString());
        assertThrows(IllegalArgumentException.class, () -> writer.writeMetadata("a:b", "x"));
        assertThrows(IllegalArgumentException.class, () -> writer.writeMetadata("note", "two\nlines"));
        assertThrows(IllegalArgumentException.class, () -> writer.writeComment("two\rlines"));
    }

    @Test
    void writesTimestampedFrameWithItsWallClock() throws IOException {
        StringWriter out = new StringWriter();
        CaptureWriter writer = new CaptureWriter(out);
        writer.write(new TimestampedFrame(CanFrame.standard(0x7E8, 0x01), 42L, T0));
        writer.flush();
        assertEquals("(1729600000.123456) can0 7E8#01\n", out.toString());
    }

    @Test
    void rejectsBadInterfaceNames() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureWriter(new StringWriter(), "can 0"));
        assertThrows(IllegalArgumentException.class, () -> new CaptureWriter(new StringWriter(), ""));
    }

    @Test
    void writeThenReadRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("captures/session.log");
        List<CanFrame> frames = List.of(
                CanFrame.standardHex(0x200, "01 C0 00 10 00 03 01"),
                CanFrame.standardHex(0x201, "00 D0 00 03 40 07 01"),
                CanFrame.extended(0x18DA_F110, 0x3E, 0x00),
                CanFrame.standard(0x7FF),
                CanFrame.standard(0x7E8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF));
        try (CaptureWriter writer = CaptureWriter.create(file)) {
            writer.writeMetadata("date", "2026-10-22");
            writer.writeMetadata("bitrate", "500000");
            writer.writeComment("free text");
            for (int i = 0; i < frames.size(); i++) {
                writer.write(T0.plusMillis(i), frames.get(i));
            }
        }
        Capture capture = CaptureReader.read(file);
        assertEquals(Map.of("date", "2026-10-22", "bitrate", "500000"), capture.metadata());
        assertEquals(List.of("date", "bitrate"), List.copyOf(capture.metadata().keySet()), "file order kept");
        assertEquals(List.of("free text"), capture.comments());
        assertEquals(frames, capture.canFrames());
        assertEquals(T0.plusMillis(4), capture.frames().get(4).timestamp());
        assertEquals("can0", capture.frames().get(0).interfaceName());
        assertTrue(Files.size(file) > 0);
    }

    @Test
    @DisplayName("reads sample lines from real candump -L output")
    void readsRealCandumpOutput() throws IOException {
        // Lines as candump -L writes them (a vcan0 sample session), plus an extended frame and an empty one.
        Capture capture = read("""
                (1436509052.249713) vcan0 044#2A366C2BBA
                (1436509052.449847) vcan0 0F6#7ADFE07BD2
                (1436509052.650004) vcan0 236#C3406B09F4C88036
                (1436509053.050284) vcan0 17F#C7
                (1436509054.251081) vcan0 1D6F5D8E#0E5A9F
                (1436509054.451166) vcan0 3C2#
                """);
        assertEquals(6, capture.frames().size());
        CapturedFrame first = capture.frames().getFirst();
        assertEquals(Instant.ofEpochSecond(1_436_509_052L, 249_713_000), first.timestamp());
        assertEquals("vcan0", first.interfaceName());
        assertEquals(CanFrame.standardHex(0x044, "2A 36 6C 2B BA"), first.frame());
        assertEquals(CanFrame.standardHex(0x236, "C3 40 6B 09 F4 C8 80 36"), capture.frames().get(2).frame());
        assertEquals(CanFrame.extendedHex(0x1D6F_5D8E, "0E 5A 9F"), capture.frames().get(4).frame());
        assertEquals(CanFrame.standard(0x3C2), capture.frames().get(5).frame());
    }

    @Test
    void readsLowercaseBlankLinesCrlfAndDotSeparators() throws IOException {
        Capture capture = read("(1.5) can0 7e8#0a0b\r\n\r\n   \n(2.000001) can0 7E8#0A.0B\n");
        assertEquals(List.of(CanFrame.standard(0x7E8, 0x0A, 0x0B), CanFrame.standard(0x7E8, 0x0A, 0x0B)),
                capture.canFrames());
        assertEquals(Instant.ofEpochSecond(1, 500_000_000), capture.frames().get(0).timestamp());
    }

    @ParameterizedTest(name = "line 3 rejected: {0}")
    @CsvSource(delimiter = '|', value = {
            "1729600000.0 can0 7E0#00            | expected '('",
            "(1729600000.0 can0 7E0#00           | missing ')'",
            "(17296x0000.0) can0 7E0#00          | bad timestamp",
            "(1729600000.) can0 7E0#00           | bad timestamp",
            "(1729600000.0) can0                 | expected '<interface> <id>#<data>'",
            "(1729600000.0) can0 7E0#00 extra    | expected '<interface> <id>#<data>'",
            "(1729600000.0) can0 7E000           | missing '#'",
            "(1729600000.0) can0 7E#00           | must be 3 (standard) or 8 (extended)",
            "(1729600000.0) can0 7G0#00          | bad hex in CAN ID",
            "(1729600000.0) can0 800#00          | exceeds 7FF",
            "(1729600000.0) can0 20000080#00     | error frames",
            "(1729600000.0) can0 7E0#0           | bad hex data",
            "(1729600000.0) can0 7E0#ZZ          | bad hex data",
            "(1729600000.0) can0 7E0#000102030405060708 | more than 8 data bytes",
            "(1729600000.0) can0 7E0#R           | remote frames",
            "(1729600000.0) can0 7E0##10011      | CAN FD",
    })
    void rejectsMalformedLinesWithLineNumber(String badLine, String expectedFragment) {
        String text = "# bitrate: 500000\n(1729600000.000000) can0 7E0#00\n" + badLine + "\n";
        CaptureFormatException e = assertThrows(CaptureFormatException.class, () -> read(text));
        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().startsWith("line 3: "), e.getMessage());
        assertTrue(e.getMessage().contains(expectedFragment),
                "expected '" + expectedFragment + "' in: " + e.getMessage());
    }

    @Test
    void metadataParsing() throws IOException {
        Capture capture = read("""
                #tool: macvagdiag 0.1
                # note: VCDS auto-scan: engine off
                #
                # just a comment
                """);
        assertEquals(Map.of("tool", "macvagdiag 0.1", "note", "VCDS auto-scan: engine off"), capture.metadata());
        assertEquals(List.of("", "just a comment"), capture.comments());
        assertEquals(List.of(), capture.frames());
    }
}
