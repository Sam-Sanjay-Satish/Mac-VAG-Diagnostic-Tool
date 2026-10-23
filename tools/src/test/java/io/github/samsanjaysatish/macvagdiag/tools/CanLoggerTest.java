package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import io.github.samsanjaysatish.macvagdiag.can.CaptureWriter;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.ReplayAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.Reply;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanLoggerTest {

    /** Standard and extended IDs, an empty frame, and a repeated ID. */
    static final String RECORDED = """
            (1729600000.000100) can0 200#C001
            (1729600000.001200) can0 7E0#0210030000000000
            (1729600000.002300) can0 18DAF110#0322F19000000000
            (1729600000.003400) can0 201#
            (1729600000.004500) can0 200#C002
            """;

    private static final Pattern CANDUMP_LINE =
            Pattern.compile("\\(\\d+\\.\\d{6}\\) can0 ([0-9A-F]{3}|[0-9A-F]{8})#([0-9A-F]{2}){0,8}");

    @TempDir
    Path dir;

    private final ByteArrayOutputStream consoleBytes = new ByteArrayOutputStream();
    private final PrintStream console = new PrintStream(consoleBytes, true, StandardCharsets.UTF_8);

    private String console() {
        return consoleBytes.toString(StandardCharsets.UTF_8);
    }

    private static Capture recorded() throws IOException {
        return CaptureReader.read(new StringReader(RECORDED));
    }

    private CanLogger logger(CanAdapter adapter, CanLogger.CaptureOpener opener, TimeSource time,
                             Optional<Duration> maxDuration) {
        return new CanLogger(adapter, opener,
                new CanLogger.Header(Bitrate.KBIT_500, TestEnv.CANABLE_PORT, TestEnv.FIRMWARE, Optional.of("unit test")),
                time, ZoneOffset.UTC, console, CanLogger.adapterErrorCounter(adapter),
                CanLogger.Timing.defaults(maxDuration), "capture.log");
    }

    private CanLogger fileLogger(CanAdapter adapter, Path file, TimeSource time, Optional<Duration> maxDuration) {
        return logger(adapter, () -> CaptureWriter.create(file), time, maxDuration);
    }

    private static List<String> frameLines(String text) {
        return text.lines().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
    }

    @Test
    void replayEndsUpInTheFileInOrderInCandumpFormatWithTheHeader() throws IOException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        ReplayAdapter adapter = ReplayAdapter.passive(recorded(), ReplayAdapter.Pacing.RECORDED, time);
        Path file = dir.resolve("capture.log");

        CanLogger.End end = fileLogger(adapter, file, time, Optional.of(Duration.ofSeconds(10))).run();

        assertEquals(CanLogger.End.DURATION_REACHED, end);
        String text = Files.readString(file);
        List<String> lines = frameLines(text);
        assertEquals(RECORDED.lines().toList(), lines, "every frame, in order, byte for byte");
        lines.forEach(l -> assertTrue(CANDUMP_LINE.matcher(l).matches(), "candump line: " + l));

        Capture written = CaptureReader.read(file);
        assertEquals(recorded().frames(), written.frames());
        assertEquals("2026-01-01T00:00:00Z", written.metadata().get("date"));
        assertEquals("500000", written.metadata().get("bitrate"));
        assertEquals("LISTEN_ONLY", written.metadata().get("mode"));
        assertEquals(TestEnv.CANABLE_PORT, written.metadata().get("port"));
        assertEquals(TestEnv.FIRMWARE, written.metadata().get("firmware"));
        assertEquals("macvagdiag-tools 0.1.0-dev", written.metadata().get("tool"));
        assertEquals("unit test", written.metadata().get("note"));
        assertTrue(text.startsWith("# macvagdiag can-log capture"), text);
        assertTrue(text.indexOf("# note:") < text.indexOf("(1729600000"), "header before the frames");
        assertTrue(console().contains("duration reached after 0:00:10: 5 frames, 4 distinct IDs, 0 errors"),
                console());
    }

    @Test
    void neverTransmitsAndOpensOnlyInListenOnly() throws IOException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        // A fake ECU that would answer anything sent to it: proves nothing was.
        SimulatedAdapter adapter = new SimulatedAdapter(time,
                sent -> List.of(Reply.now(CanFrame.standardHex(0x7E8, "02 50 03"))));
        CanLogger logger = fileLogger(adapter, dir.resolve("c.log"), time, Optional.of(Duration.ofSeconds(3)));
        logger.start();
        assertEquals(Optional.of(AdapterMode.LISTEN_ONLY), adapter.mode());
        adapter.inject(CanFrame.standardHex(0x200, "01"));
        logger.loop();

        assertEquals(List.of(new SimulatedAdapter.OpenEvent(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY)),
                adapter.openEvents());
        assertEquals(List.of(), adapter.sentFrames(), "zero frames sent");
        assertEquals(1, logger.frameCount());
        assertEquals(AdapterMode.LISTEN_ONLY, CanLogger.MODE);
    }

    @Test
    void flushesAboutEverySecond() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        Path file = dir.resolve("c.log");
        CanLogger logger = fileLogger(adapter, file, time, Optional.empty());
        logger.start();
        assertTrue(Files.readString(file).contains("# mode: LISTEN_ONLY"), "header is flushed at start");

        adapter.inject(CanFrame.standardHex(0x200, "01"));
        adapter.inject(CanFrame.standardHex(0x201, "02"));
        assertTrue(logger.step());
        assertTrue(logger.step());
        assertEquals(Duration.ZERO, time.elapsed());
        assertEquals(List.of(), frameLines(Files.readString(file)), "not yet flushed");

        assertTrue(logger.step()); // waits until the flush is due, then flushes
        assertEquals(Duration.ofSeconds(1), time.elapsed());
        assertEquals(2, frameLines(Files.readString(file)).size(), "flushed after 1 s");

        adapter.inject(CanFrame.standardHex(0x202, "03"));
        assertTrue(logger.step());
        assertTrue(logger.step());
        assertEquals(Duration.ofSeconds(2), time.elapsed());
        assertEquals(3, frameLines(Files.readString(file)).size());
        logger.shutdown();
    }

    @Test
    void printsALiveSummaryEveryFiveSeconds() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        CanLogger logger = fileLogger(adapter, dir.resolve("c.log"), time, Optional.empty());
        logger.start();
        adapter.inject(CanFrame.standardHex(0x200, "01"));
        adapter.inject(CanFrame.extendedHex(0x18DAF110, "02"));
        adapter.inject(CanFrame.standardHex(0x200, "03"));
        for (int i = 0; i < 3; i++) {
            assertTrue(logger.step());
        }
        for (int second = 1; second <= 4; second++) {
            assertTrue(logger.step());
        }
        assertEquals("", console(), "no summary before 5 s");
        assertTrue(logger.step());
        assertEquals(Duration.ofSeconds(5), time.elapsed());
        assertEquals("can-log: 0:00:05  0.6 frames/s  3 frames  2 IDs  0 errors" + System.lineSeparator(),
                console());
        for (int second = 6; second <= 10; second++) {
            assertTrue(logger.step());
        }
        assertTrue(console().contains("can-log: 0:00:10  0.0 frames/s  3 frames  2 IDs  0 errors"), console());
        logger.shutdown();
    }

    /** Records flush and close calls on the capture's underlying writer. */
    private static final class RecordingWriter extends StringWriter {
        volatile String flushed = "";
        volatile boolean closed;

        @Override
        public void flush() {
            flushed = toString();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void shutdownHookClosesTheAdapterFlushesAndClosesTheFileAndPrintsStats() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        RecordingWriter out = new RecordingWriter();
        CanLogger logger = logger(adapter, () -> new CaptureWriter(out), time, Optional.empty());
        logger.start();
        adapter.inject(CanFrame.standardHex(0x200, "01"));
        adapter.inject(CanFrame.standardHex(0x7E0, "02 10 03"));
        assertTrue(logger.step());
        assertTrue(logger.step());
        assertFalse(out.flushed.contains("7E0#021003"), "second frame not flushed yet");

        logger.shutdown(); // what the Ctrl-C hook runs

        assertFalse(adapter.isOpen(), "adapter closed");
        assertTrue(out.closed, "file closed");
        assertEquals(2, frameLines(out.flushed).size(), "flushed before closing: " + out.flushed);
        assertEquals(Optional.of(CanLogger.End.STOPPED), logger.end());
        assertTrue(console().contains("can-log: stopped after 0:00:00: 2 frames, 2 distinct IDs, 0 errors"),
                console());
        assertFalse(logger.step(), "nothing more is logged");

        String before = console();
        logger.shutdown();
        assertEquals(before, console(), "second shutdown does nothing");
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    void ctrlCWhileBlockedInReceiveStopsTheLoopCleanly() throws Exception {
        FakeTimeSource time = new FakeTimeSource(); // manual: the loop blocks until something happens
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        RecordingWriter out = new RecordingWriter();
        CanLogger logger = logger(adapter, () -> new CaptureWriter(out), time, Optional.empty());
        logger.start();
        adapter.inject(CanFrame.standardHex(0x200, "01"));
        AtomicReference<CanLogger.End> result = new AtomicReference<>();
        Thread loop = new Thread(() -> result.set(logger.loop()), "logger-loop");
        loop.start();
        time.awaitWaiters(1); // the loop is now waiting for the next frame

        logger.shutdown();
        loop.join(Duration.ofSeconds(5));

        assertFalse(loop.isAlive());
        assertEquals(CanLogger.End.STOPPED, result.get(), "a stop, not an adapter failure");
        assertTrue(out.closed);
        assertEquals(1, frameLines(out.flushed).size());
        assertFalse(console().contains("adapter failed"), console());
        assertEquals(1, console().split("can-log: stopped", -1).length - 1, "final stats printed once");
    }

    @Test
    void unpluggedAdapterKeepsTheFramesReceivedBeforeAndClosesTheFile() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        Path file = dir.resolve("c.log");
        CanLogger logger = fileLogger(adapter, file, time, Optional.empty());
        logger.start();
        adapter.inject(CanFrame.standardHex(0x200, "01"));
        adapter.inject(CanFrame.standardHex(0x201, "02"));
        adapter.failReceive(new CanAdapterException("serial port unplugged"));

        assertEquals(CanLogger.End.ADAPTER_FAILED, logger.loop());
        assertTrue(CanLogger.End.ADAPTER_FAILED.isFailure());
        assertEquals(2, CaptureReader.read(file).frames().size());
        assertFalse(adapter.isOpen());
        assertTrue(console().contains("adapter failed: serial port unplugged"), console());
        assertTrue(console().contains("can-log: adapter failed after"), console());
    }

    @Test
    void adapterThatRefusesToOpenCreatesNoFile() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        adapter.failNextOpen(() -> new CanAdapterException("refuses LISTEN_ONLY"));
        Path file = dir.resolve("c.log");
        CanLogger logger = fileLogger(adapter, file, time, Optional.empty());

        assertThrows(CanAdapterException.class, logger::start);
        assertFalse(Files.exists(file));
        logger.shutdown(); // the hook still runs at exit; harmless
        assertEquals("", console());
    }

    @Test
    void fileThatCannotBeCreatedClosesTheAdapter() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        CanLogger logger = logger(adapter, () -> {
            throw new IOException("disk full");
        }, time, Optional.empty());

        assertEquals("disk full", assertThrows(IOException.class, logger::start).getMessage());
        assertFalse(adapter.isOpen());
    }

    @Test
    void writeFailureStopsTheLogger() throws Exception {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        // Accepts the header, fails on the first frame line.
        Writer broken = new FilterWriter(new StringWriter()) {
            @Override
            public void write(String str, int off, int len) throws IOException {
                if (str.startsWith("(")) {
                    throw new IOException("disk full");
                }
                super.write(str, off, len);
            }
        };
        CanLogger logger = logger(adapter, () -> new CaptureWriter(broken), time, Optional.empty());
        logger.start();
        adapter.inject(CanFrame.standardHex(0x200, "01"));

        assertEquals(CanLogger.End.WRITE_FAILED, logger.loop());
        assertTrue(console().contains("could not write capture.log: disk full"), console());
        assertEquals(1, logger.errors());
        assertFalse(adapter.isOpen());
    }
}
