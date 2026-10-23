package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parsed {@code can-log} arguments. There is deliberately no option for the adapter mode: the logger
 * only ever opens in {@code LISTEN_ONLY}.
 *
 * @param port serial port path, or empty to auto-detect the only CANable
 * @param bitrate CAN bitrate (default {@link #DEFAULT_BITRATE})
 * @param output capture file (default {@code local/captures/<date>-<time>.log})
 * @param note free-text note for the capture header
 * @param duration stop after this long; empty runs until Ctrl-C
 */
record CanLogOptions(Optional<String> port, Bitrate bitrate, Path output, Optional<String> note,
                     Optional<Duration> duration) {

    static final Bitrate DEFAULT_BITRATE = Bitrate.KBIT_500;
    /** Raw captures contain the VIN, so they go in the git-ignored {@code local/} directory. */
    static final Path DEFAULT_OUTPUT_DIR = Path.of("local", "captures");
    private static final DateTimeFormatter FILE_NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss'.log'", Locale.ROOT);

    private static final String PORT = "--port";
    private static final String BITRATE = "--bitrate";
    private static final String OUTPUT = "--output";
    private static final String NOTE = "--note";
    private static final String DURATION = "--duration";
    private static final Set<String> OPTIONS = Set.of(PORT, BITRATE, OUTPUT, NOTE, DURATION);

    static final String USAGE = """
            Usage: %s can-log [--port <path>] [--bitrate <bit/s>] [--output <file>] [--note <text>] [--duration <seconds>]
              --port      serial port, e.g. /dev/cu.usbmodem14101 (default: the only CANable plugged in)
              --bitrate   CAN bitrate in bit/s, or with a k suffix: 500000 or 500k (default 500k)
              --output    capture file (default: local/captures/<date>-<time>.log); never overwritten
              --note      free-text note for the capture header, e.g. "VCDS auto-scan, engine off"
              --duration  stop after this many seconds (default: run until Ctrl-C)
            The adapter is opened in listen-only mode only; can-log never transmits.""".formatted(Main.TOOL_NAME);

    /**
     * Parses {@code args}. Options take a value as the next argument or after {@code =}.
     *
     * @param now when the logger starts, for the default file name
     * @param zone time zone for the default file name
     */
    static CanLogOptions parse(List<String> args, Instant now, ZoneId zone) throws UsageException {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (!OPTIONS.contains(name)) {
                throw new UsageException("unknown argument '" + arg + "'");
            }
            if (value == null) {
                if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                    throw new UsageException(name + " needs a value");
                }
                value = args.get(++i);
            }
            if (values.put(name, value) != null) {
                throw new UsageException(name + " given more than once");
            }
        }

        Optional<String> port = Optional.ofNullable(values.get(PORT));
        if (port.isPresent() && port.get().isBlank()) {
            throw new UsageException("--port is empty");
        }
        Bitrate bitrate = values.containsKey(BITRATE) ? parseBitrate(values.get(BITRATE)) : DEFAULT_BITRATE;
        Path output = values.containsKey(OUTPUT)
                ? parsePath(values.get(OUTPUT))
                : DEFAULT_OUTPUT_DIR.resolve(FILE_NAME.format(now.atZone(zone)));
        Optional<String> note = Optional.ofNullable(values.get(NOTE));
        if (note.isPresent() && (note.get().indexOf('\n') >= 0 || note.get().indexOf('\r') >= 0)) {
            throw new UsageException("--note must be a single line");
        }
        Optional<Duration> duration = values.containsKey(DURATION)
                ? Optional.of(parseDuration(values.get(DURATION)))
                : Optional.empty();
        return new CanLogOptions(port, bitrate, output, note, duration);
    }

    /** {@code 500000} or {@code 500k}. */
    static Bitrate parseBitrate(String text) throws UsageException {
        String s = text.strip().toLowerCase(Locale.ROOT);
        int multiplier = 1;
        if (s.endsWith("k")) {
            multiplier = 1000;
            s = s.substring(0, s.length() - 1);
        }
        int bitsPerSecond;
        try {
            bitsPerSecond = Math.multiplyExact(Integer.parseInt(s), multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new UsageException("bad bitrate '" + text + "'; " + supportedBitrates());
        }
        try {
            return Bitrate.ofBitsPerSecond(bitsPerSecond);
        } catch (IllegalArgumentException e) {
            throw new UsageException("unsupported bitrate '" + text + "'; " + supportedBitrates());
        }
    }

    private static String supportedBitrates() {
        return "supported (bit/s): " + Arrays.stream(Bitrate.values())
                .map(b -> Integer.toString(b.bitsPerSecond()))
                .collect(Collectors.joining(", "));
    }

    private static Path parsePath(String text) throws UsageException {
        if (text.isBlank()) {
            throw new UsageException("--output is empty");
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new UsageException("bad output path '" + text + "': " + e.getReason());
        }
    }

    /** Whole seconds, optionally with an {@code s} suffix. */
    private static Duration parseDuration(String text) throws UsageException {
        String s = text.strip();
        if (s.endsWith("s")) {
            s = s.substring(0, s.length() - 1);
        }
        long seconds;
        try {
            seconds = Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new UsageException("bad duration '" + text + "'; give whole seconds, e.g. 600");
        }
        if (seconds <= 0) {
            throw new UsageException("duration must be positive, got '" + text + "'");
        }
        return Duration.ofSeconds(seconds);
    }
}
