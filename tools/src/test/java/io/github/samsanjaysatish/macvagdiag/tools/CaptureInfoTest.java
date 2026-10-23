package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureInfoTest {

    /** Hand-written: header, 6 frames over 2.5 s, three IDs, one extended. */
    private static final String CAPTURE = """
            # macvagdiag can-log capture in candump log format (candump -L)
            # date: 2026-10-22T14:30:05Z
            # bitrate: 500000
            # note: VCDS auto-scan, engine off
            (1761143405.000000) can0 200#C001
            (1761143405.010000) can0 201#
            (1761143405.020000) can0 200#C002
            (1761143407.000000) can0 18DAF110#0322F190
            (1761143407.400000) can0 200#C003
            (1761143407.500000) can0 7E0#0210030000000000
            """;

    @TempDir
    Path dir;

    @Test
    void summarizesAHandWrittenCapture() throws IOException {
        CaptureInfo info = CaptureInfo.of(CaptureReader.read(new StringReader(CAPTURE)));

        assertEquals(6, info.frameCount());
        assertEquals(Instant.parse("2025-10-22T14:30:05Z"), info.first().orElseThrow());
        assertEquals(Instant.parse("2025-10-22T14:30:07.500Z"), info.last().orElseThrow());
        assertEquals(Duration.ofMillis(2500), info.duration());
        assertEquals(Duration.ofMillis(1980), info.largestGap());
        assertEquals("500000", info.metadata().get("bitrate"));
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        info.idCounts().forEach((id, n) -> counts.put(id.toString(), n));
        assertEquals(List.of("200", "201", "7E0", "18DAF110"), List.copyOf(counts.keySet()),
                "standard IDs first, then extended, each by ID");
        assertEquals(List.of(3L, 1L, 1L, 1L), List.copyOf(counts.values()));
    }

    @Test
    void commandPrintsTheSummary() throws IOException {
        Path file = Files.writeString(dir.resolve("session.log"), CAPTURE);
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of());

        assertEquals(0, t.run("capture-info", file.toString()), t.err());

        String out = t.out();
        assertTrue(out.contains("Capture:      " + file), out);
        assertTrue(out.contains("  note: VCDS auto-scan, engine off"), out);
        assertTrue(out.contains("Frames:       6"), out);
        assertTrue(out.contains("First frame:  2025-10-22 14:30:05.000000 +00:00"), out);
        assertTrue(out.contains("Last frame:   2025-10-22 14:30:07.500000 +00:00"), out);
        assertTrue(out.contains("Duration:     2.500 s"), out);
        assertTrue(out.contains("Largest gap:  1.980 s"), out);
        assertTrue(out.contains("Distinct IDs: 4"), out);
        assertTrue(out.contains("  200       3"), out);
        assertTrue(out.contains("  18DAF110  1"), out);
        assertEquals("", t.err());
    }

    @Test
    void emptyCaptureIsReportedAndExitsOne() throws IOException {
        Path file = Files.writeString(dir.resolve("empty.log"), "# bitrate: 500000\n");
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of());
        assertEquals(1, t.run("capture-info", file.toString()));
        assertTrue(t.out().contains("Frames:       0"), t.out());
        assertTrue(t.out().contains("Duration:     0.000 s"), t.out());
        assertTrue(t.err().contains("no frames"), t.err());
    }

    @Test
    void malformedCaptureNamesTheLine() throws IOException {
        Path file = Files.writeString(dir.resolve("bad.log"), "(1.000000) can0 200#01\n(2.000000) can0 200#0\n");
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of());
        assertEquals(1, t.run("capture-info", file.toString()));
        assertTrue(t.err().contains("line 2"), t.err());
    }

    @Test
    void missingFileAndMissingArgument() {
        TestEnv t = new TestEnv(new FakeTimeSource(), List.of());
        assertEquals(1, t.run("capture-info", dir.resolve("nope.log").toString()));
        assertTrue(t.err().contains("not found"), t.err());
        assertEquals(2, new TestEnv(new FakeTimeSource(), List.of()).run("capture-info"));
    }
}
