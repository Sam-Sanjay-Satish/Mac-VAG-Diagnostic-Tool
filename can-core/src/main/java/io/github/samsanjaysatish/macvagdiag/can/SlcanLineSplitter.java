package io.github.samsanjaysatish.macvagdiag.can;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the byte stream from an slcan adapter into lines. Lines can arrive split across reads, or
 * several in one read.
 *
 * <ul>
 *   <li>CR ends a line (returned without the CR).</li>
 *   <li>BEL (0x07) is Lawicel's error reply and has no CR; it is returned as the line {@code "\u0007"}.</li>
 *   <li>LF, NUL and bytes outside printable ASCII are stray and dropped.</li>
 *   <li>A line longer than {@link #MAX_LINE_LENGTH} is discarded up to its CR (counted in
 *       {@link #discardedLines()}), so a stream with no CR cannot grow memory without bound.</li>
 * </ul>
 *
 * Not thread-safe; one splitter belongs to one reader thread.
 */
public final class SlcanLineSplitter {

    /**
     * Longer than any line the CANable firmware sends: a classic frame is at most 26 characters, a
     * 64-byte CAN FD frame 138 (its {@code SLCAN_MTU} is 155 with CR and padding).
     */
    public static final int MAX_LINE_LENGTH = 160;

    private final StringBuilder current = new StringBuilder();
    private boolean overflowed;
    private long discardedLines;

    /** Feeds {@code length} bytes from {@code buffer} and returns the lines they complete. */
    public List<String> feed(byte[] buffer, int length) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int b = buffer[i] & 0xFF;
            if (b == SlcanCodec.CR) {
                if (overflowed) {
                    discardedLines++;
                    overflowed = false;
                } else {
                    lines.add(current.toString());
                }
                current.setLength(0);
            } else if (b == SlcanCodec.BELL) {
                lines.add(String.valueOf(SlcanCodec.BELL));
            } else if (b >= 0x20 && b <= 0x7E) {
                if (current.length() >= MAX_LINE_LENGTH) {
                    overflowed = true;
                    current.setLength(0);
                }
                if (!overflowed) {
                    current.append((char) b);
                }
            }
            // anything else (LF, NUL, other control bytes, non-ASCII) is stray and dropped
        }
        return lines;
    }

    /** {@link #feed(byte[], int)} for the whole array. */
    public List<String> feed(byte[] bytes) {
        return feed(bytes, bytes.length);
    }

    /** Over-long lines thrown away so far. */
    public long discardedLines() {
        return discardedLines;
    }
}
