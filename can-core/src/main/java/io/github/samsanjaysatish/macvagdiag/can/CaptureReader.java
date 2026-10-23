package io.github.samsanjaysatish.macvagdiag.can;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the candump log format ({@code candump -L}): {@code (1729600000.123456) can0 7E0#0210030000000000}.
 *
 * <ul>
 *   <li>3 hex ID digits is a standard frame, 8 an extended one; hex may be upper- or lowercase.</li>
 *   <li>Lines starting with {@code #} are comments; {@code # key: value} is metadata.</li>
 *   <li>Blank lines are skipped.</li>
 *   <li>Remote frames ({@code 123#R}), CAN FD ({@code 123##1...}) and error frames are rejected,
 *       since {@link CanFrame} models classic data frames only.</li>
 * </ul>
 * Any bad line fails the whole read with a {@link CaptureFormatException} naming the line number.
 */
public final class CaptureReader {

    private static final int MAX_FRACTION_DIGITS = 9;

    private CaptureReader() {
    }

    public static Capture read(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static Capture read(Reader in) throws IOException {
        BufferedReader reader = in instanceof BufferedReader b ? b : new BufferedReader(in);
        Map<String, String> metadata = new LinkedHashMap<>();
        List<String> comments = new ArrayList<>();
        List<CapturedFrame> frames = new ArrayList<>();
        String raw;
        int lineNumber = 0;
        while ((raw = reader.readLine()) != null) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '#') {
                String text = line.substring(1).strip();
                int colon = text.indexOf(':');
                if (colon > 0) {
                    metadata.put(text.substring(0, colon).strip(), text.substring(colon + 1).strip());
                } else {
                    comments.add(text);
                }
                continue;
            }
            frames.add(parseLine(line, lineNumber));
        }
        return new Capture(metadata, comments, frames);
    }

    /** Parses one frame line (already stripped of surrounding whitespace). */
    static CapturedFrame parseLine(String line, int lineNumber) throws CaptureFormatException {
        if (line.charAt(0) != '(') {
            throw new CaptureFormatException(lineNumber, "expected '(' starting the timestamp", line);
        }
        int close = line.indexOf(')');
        if (close < 0) {
            throw new CaptureFormatException(lineNumber, "missing ')' after the timestamp", line);
        }
        Instant timestamp = parseTimestamp(line.substring(1, close), lineNumber, line);
        String[] fields = line.substring(close + 1).strip().split("\\s+");
        if (fields.length != 2 || fields[0].isEmpty()) {
            throw new CaptureFormatException(lineNumber, "expected '<interface> <id>#<data>' after the timestamp", line);
        }
        return new CapturedFrame(timestamp, fields[0], parseFrame(fields[1], lineNumber, line));
    }

    private static Instant parseTimestamp(String text, int lineNumber, String line) throws CaptureFormatException {
        int dot = text.indexOf('.');
        String seconds = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);
        if (seconds.isEmpty() || !allDigits(seconds) || dot >= 0 && (fraction.isEmpty() || !allDigits(fraction))
                || fraction.length() > MAX_FRACTION_DIGITS || seconds.length() > 12) {
            throw new CaptureFormatException(lineNumber, "bad timestamp '" + text + "'", line);
        }
        long nanos = fraction.isEmpty() ? 0 : Long.parseLong((fraction + "000000000").substring(0, MAX_FRACTION_DIGITS));
        return Instant.ofEpochSecond(Long.parseLong(seconds), nanos);
    }

    private static CanFrame parseFrame(String text, int lineNumber, String line) throws CaptureFormatException {
        int hash = text.indexOf('#');
        if (hash < 0) {
            throw new CaptureFormatException(lineNumber, "missing '#' between ID and data", line);
        }
        String idText = text.substring(0, hash);
        String dataText = text.substring(hash + 1);
        boolean extended;
        if (idText.length() == SlcanCodec.STANDARD_ID_DIGITS) {
            extended = false;
        } else if (idText.length() == SlcanCodec.EXTENDED_ID_DIGITS) {
            extended = true;
        } else {
            throw new CaptureFormatException(lineNumber,
                    "CAN ID must be 3 (standard) or 8 (extended) hex digits, got '" + idText + "'", line);
        }
        if (!allHex(idText)) {
            throw new CaptureFormatException(lineNumber, "bad hex in CAN ID '" + idText + "'", line);
        }
        long id = Long.parseLong(idText, 16);
        if (extended && id > CanFrame.MAX_EXTENDED_ID) {
            throw new CaptureFormatException(lineNumber,
                    "error frames and ID flags are not supported (ID " + idText + ")", line);
        }
        if (!extended && id > CanFrame.MAX_STANDARD_ID) {
            throw new CaptureFormatException(lineNumber, "standard CAN ID " + idText + " exceeds 7FF", line);
        }
        if (dataText.startsWith("#")) {
            throw new CaptureFormatException(lineNumber, "CAN FD frames are not supported", line);
        }
        if (dataText.startsWith("R") || dataText.startsWith("r")) {
            throw new CaptureFormatException(lineNumber, "remote frames are not supported", line);
        }
        // can-utils accepts '.' between data bytes (11.22.33); candump itself never writes them.
        String data = dataText.replace(".", "");
        if (data.length() % 2 != 0 || !allHex(data)) {
            throw new CaptureFormatException(lineNumber, "bad hex data '" + dataText + "'", line);
        }
        if (data.length() / 2 > CanFrame.MAX_DATA_LENGTH) {
            throw new CaptureFormatException(lineNumber, "more than 8 data bytes", line);
        }
        return new CanFrame((int) id, extended, Hex.parse(data));
    }

    private static boolean allDigits(String s) {
        return s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean allHex(String s) {
        return s.chars().allMatch(c -> Hex.digit((char) c) >= 0);
    }
}
