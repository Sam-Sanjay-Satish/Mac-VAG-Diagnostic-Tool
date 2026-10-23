package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlcanLineSplitterTest {

    private final SlcanLineSplitter splitter = new SlcanLineSplitter();

    private List<String> feed(String ascii) {
        return splitter.feed(ascii.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("t7E8... split across three reads becomes one line")
    void lineSplitAcrossReads() {
        assertEquals(List.of(), feed("t7E8806"));
        assertEquals(List.of(), feed("41000000"));
        assertEquals(List.of("t7E880641000000000000"), feed("000000\r"));
    }

    @Test
    void severalLinesInOneRead() {
        assertEquals(List.of("t7E880641000000000000", "t20130102", "T18DAF11023E00"),
                feed("t7E880641000000000000\rt20130102\rT18DAF11023E00\r"));
    }

    @Test
    void severalLinesWithASplitTail() {
        assertEquals(List.of("t2000", "t2011"), feed("t2000\rt2011\rt20"));
        assertEquals(List.of("t2021AA"), feed("21AA\r"));
    }

    @Test
    @DisplayName("stray LF, NUL and non-ASCII bytes are dropped")
    void strayBytesDropped() {
        assertEquals(List.of("t2000", "t2011"), splitter.feed(new byte[] {
                '\n', 0x00, 't', '2', '0', '0', '0', '\r', '\n', (byte) 0xFF, 't', '2', '0', '1', '1', '\r'}));
    }

    @Test
    @DisplayName("\\a (BEL) is its own line, even with no CR")
    void bellIsAnErrorLine() {
        assertEquals(List.of("\u0007", "", "\u0007"), feed("\u0007\r\u0007"));
    }

    @Test
    void emptyLinesAreKeptAsAcks() {
        assertEquals(List.of("", ""), feed("\r\r"));
    }

    @Test
    void respectsLengthArgument() {
        byte[] buffer = "t2000\rGARBAGE".getBytes(StandardCharsets.US_ASCII);
        assertEquals(List.of("t2000"), splitter.feed(buffer, 6));
    }

    @Test
    void overlongLineIsDiscardedUpToItsCr() {
        String junk = "A".repeat(SlcanLineSplitter.MAX_LINE_LENGTH + 10);
        assertEquals(List.of("t2000"), feed(junk + "\rt2000\r"));
        assertEquals(1, splitter.discardedLines());
    }

    @Test
    void lineOfExactlyMaxLengthIsKept() {
        String line = "A".repeat(SlcanLineSplitter.MAX_LINE_LENGTH);
        assertEquals(List.of(line), feed(line + "\r"));
        assertEquals(0, splitter.discardedLines());
    }
}
