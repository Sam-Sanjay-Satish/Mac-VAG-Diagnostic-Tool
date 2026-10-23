package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanLogOptionsTest {

    private static final Instant NOW = Instant.parse("2026-10-22T14:30:05Z");

    private static CanLogOptions parse(String... args) throws UsageException {
        return CanLogOptions.parse(List.of(args), NOW, ZoneOffset.UTC);
    }

    @Test
    void defaults() throws UsageException {
        CanLogOptions o = parse();
        assertEquals(Optional.empty(), o.port(), "no port: auto-detect");
        assertEquals(Bitrate.KBIT_500, o.bitrate());
        assertEquals(Optional.empty(), o.note());
        assertEquals(Optional.empty(), o.duration(), "runs until Ctrl-C");
    }

    @Test
    void defaultOutputIsUnderLocalCapturesNamedByDateAndTime() throws UsageException {
        Path output = parse().output();
        assertEquals(Path.of("local", "captures", "2026-10-22-143005.log"), output);
        assertTrue(CanLogCommand.isInsideLocal(output));
    }

    @Test
    void defaultOutputUsesTheLocalTimeZone() throws UsageException {
        Path output = CanLogOptions.parse(List.of(), NOW, ZoneId.of("Europe/Berlin")).output();
        assertEquals(Path.of("local", "captures", "2026-10-22-163005.log"), output);
    }

    @Test
    void allOptions() throws UsageException {
        CanLogOptions o = parse("--port", "/dev/cu.usbmodem1", "--bitrate", "250000", "--output", "x/y.log",
                "--note", "VCDS auto-scan, engine off", "--duration", "600");
        assertEquals(Optional.of("/dev/cu.usbmodem1"), o.port());
        assertEquals(Bitrate.KBIT_250, o.bitrate());
        assertEquals(Path.of("x/y.log"), o.output());
        assertEquals(Optional.of("VCDS auto-scan, engine off"), o.note());
        assertEquals(Optional.of(Duration.ofSeconds(600)), o.duration());
    }

    @Test
    void optionsAcceptEqualsForm() throws UsageException {
        CanLogOptions o = parse("--port=/dev/cu.usbmodem1", "--bitrate=500k", "--duration=10s");
        assertEquals(Optional.of("/dev/cu.usbmodem1"), o.port());
        assertEquals(Bitrate.KBIT_500, o.bitrate());
        assertEquals(Optional.of(Duration.ofSeconds(10)), o.duration());
    }

    @ParameterizedTest
    @ValueSource(strings = {"500000", "500k", "500K", " 500k "})
    void bitrateSpellings(String text) throws UsageException {
        assertEquals(Bitrate.KBIT_500, CanLogOptions.parseBitrate(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "800000", "750k", "-500000", "500kk", "99999999999", "0"})
    void badBitrateIsRejectedListingTheSupportedOnes(String text) {
        UsageException e = assertThrows(UsageException.class, () -> parse("--bitrate", text));
        assertTrue(e.getMessage().contains("bitrate"), e.getMessage());
        assertTrue(e.getMessage().contains("500000"), "lists supported bitrates: " + e.getMessage());
    }

    @Test
    void portWithoutValueIsRejected() {
        assertEquals("--port needs a value", assertThrows(UsageException.class, () -> parse("--port")).getMessage());
        assertEquals("--port needs a value",
                assertThrows(UsageException.class, () -> parse("--port", "--bitrate", "500k")).getMessage());
        assertEquals("--port is empty", assertThrows(UsageException.class, () -> parse("--port=")).getMessage());
    }

    @Test
    void unknownArgumentIsRejected() {
        // In particular there is no way to ask for another adapter mode.
        for (String arg : List.of("--mode", "--normal", "--transmit", "/dev/cu.usbmodem1")) {
            UsageException e = assertThrows(UsageException.class, () -> parse(arg, "x"));
            assertTrue(e.getMessage().startsWith("unknown argument"), e.getMessage());
        }
    }

    @Test
    void repeatedOptionIsRejected() {
        assertThrows(UsageException.class, () -> parse("--note", "a", "--note", "b"));
    }

    @Test
    void multiLineNoteIsRejected() {
        assertThrows(UsageException.class, () -> parse("--note", "one\ntwo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "ten", "1.5", "10m"})
    void badDurationIsRejected(String text) {
        assertThrows(UsageException.class, () -> parse("--duration", text));
    }
}
