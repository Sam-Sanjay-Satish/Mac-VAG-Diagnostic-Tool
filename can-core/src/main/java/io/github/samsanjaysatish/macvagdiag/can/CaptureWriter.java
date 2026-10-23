package io.github.samsanjaysatish.macvagdiag.can;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Writes the candump log format from Linux can-utils ({@code candump -L}), one frame per line:
 * <pre>(1729600000.123456) can0 7E0#0210030000000000</pre>
 * SavvyCAN, python-can and can-utils can all read it. Metadata goes in comment lines:
 * <pre># bitrate: 500000</pre>
 * Not thread-safe. Nothing is flushed until {@link #flush()} or {@link #close()}.
 */
public final class CaptureWriter implements Closeable, Flushable {

    /** The interface column used when none is given. */
    public static final String DEFAULT_INTERFACE = "can0";

    private final Writer out;
    private final String interfaceName;

    public CaptureWriter(Writer out, String interfaceName) {
        this.out = Objects.requireNonNull(out, "out");
        this.interfaceName = Objects.requireNonNull(interfaceName, "interfaceName");
        if (interfaceName.isEmpty() || interfaceName.chars().anyMatch(c -> c <= ' ' || c == '#')) {
            throw new IllegalArgumentException("Interface name must be non-empty, without spaces or '#': \""
                    + interfaceName + "\"");
        }
    }

    public CaptureWriter(Writer out) {
        this(out, DEFAULT_INTERFACE);
    }

    /** Creates (or truncates) {@code file}, creating parent directories. */
    public static CaptureWriter create(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        return new CaptureWriter(writer);
    }

    /** {@code # key: value}. The key must not contain {@code :}; neither may contain a line break. */
    public void writeMetadata(String key, String value) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isBlank() || key.contains(":") || hasLineBreak(key)) {
            throw new IllegalArgumentException("Bad metadata key: \"" + key + "\"");
        }
        if (hasLineBreak(value)) {
            throw new IllegalArgumentException("Metadata value contains a line break: \"" + value + "\"");
        }
        out.write("# " + key.strip() + ": " + value.strip() + "\n");
    }

    /** {@code # text}, a free-text comment. */
    public void writeComment(String text) throws IOException {
        if (hasLineBreak(text)) {
            throw new IllegalArgumentException("Comment contains a line break: \"" + text + "\"");
        }
        out.write("# " + text + "\n");
    }

    /** Writes a received frame with its wall-clock timestamp. */
    public void write(TimestampedFrame frame) throws IOException {
        write(frame.wallClock(), frame.frame());
    }

    /** Writes a frame read from another capture, keeping its timestamp but using this writer's interface name. */
    public void write(CapturedFrame frame) throws IOException {
        write(frame.timestamp(), frame.frame());
    }

    public void write(Instant timestamp, CanFrame frame) throws IOException {
        out.write(formatLine(timestamp, interfaceName, frame));
        out.write('\n');
    }

    /** {@code (1729600000.123456) can0 7E0#0210030000000000}, without line terminator. */
    static String formatLine(Instant timestamp, String interfaceName, CanFrame frame) {
        if (timestamp.getEpochSecond() < 0) {
            throw new IllegalArgumentException("Timestamp before 1970: " + timestamp);
        }
        String id = frame.isExtended() ? String.format("%08X", frame.id()) : String.format("%03X", frame.id());
        return String.format("(%d.%06d) %s %s#%s", timestamp.getEpochSecond(), timestamp.getNano() / 1_000,
                interfaceName, id, Hex.formatCompact(frame.data()));
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private static boolean hasLineBreak(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }
}
