package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.ReplayAdapter;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code can-log} end to end through {@link Main#run}, on fakes. */
class CanLogCommandTest {

    /** The message SlcanAdapter gives when asked for LISTEN_ONLY (its constant is package-private). */
    private static final String SLCAN_REFUSAL = "SlcanAdapter refuses LISTEN_ONLY: the CANable 2.0 stock slcan "
            + "firmware (normaldotcom/canable2-fw b158aa7) accepts the silent-mode command M1 but can_enable() "
            + "resets the controller to normal mode on O, so the adapter would still ACK and send error frames "
            + "on the bus. Not opening the port until that is resolved (see can-core/CLAUDE.md).";

    @TempDir
    Path dir;

    @Test
    void logsAReplayToTheFileAndExitsZero() throws IOException {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        Capture recorded = CaptureReader.read(new StringReader(CanLoggerTest.RECORDED));
        ReplayAdapter replay = ReplayAdapter.passive(recorded, ReplayAdapter.Pacing.IMMEDIATE, time);
        TestEnv t = new TestEnv(time, List.of(TestEnv.ARDUINO, TestEnv.CANABLE), path -> replay);
        Path out = dir.resolve("local").resolve("captures").resolve("run.log");

        int code = t.run("can-log", "--output", out.toString(), "--note", "VCDS auto-scan, engine off",
                "--duration", "3");

        assertEquals(0, code, t.err());
        assertEquals(List.of(TestEnv.CANABLE_PORT), t.requestedPorts, "auto-detected the only CANable");
        Capture written = CaptureReader.read(out);
        assertEquals(recorded.frames(), written.frames());
        assertEquals("VCDS auto-scan, engine off", written.metadata().get("note"));
        assertEquals(TestEnv.FIRMWARE, written.metadata().get("firmware"));
        assertEquals("LISTEN_ONLY", written.metadata().get("mode"));
        assertTrue(t.err().contains("listening on " + TestEnv.CANABLE_PORT + " at 500000 bit/s in LISTEN_ONLY"),
                t.err());
        assertTrue(t.err().contains("duration reached after 0:00:03: 5 frames"), t.err());
        assertFalse(t.err().contains("outside local/"), t.err());
        assertEquals("", t.out(), "can-log writes nothing to stdout");

        assertEquals(1, t.hooks.size(), "Ctrl-C hook registered");
        String before = t.err();
        t.hooks.getFirst().run(); // the JVM runs it again at exit: harmless
        assertEquals(before, t.err());
    }

    @Test
    void listenOnlyRefusalByTheAdapterIsExplainedAndNothingIsSentOrCreated() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        adapter.failNextOpen(() -> new CanAdapterException(SLCAN_REFUSAL));
        TestEnv t = new TestEnv(time, List.of(TestEnv.CANABLE), path -> adapter);
        Path out = dir.resolve("local").resolve("x.log");

        int code = t.run("can-log", "--output", out.toString());

        assertEquals(1, code);
        String err = t.err();
        assertTrue(err.contains("could not open " + TestEnv.CANABLE_PORT + " in listen-only mode"), err);
        assertTrue(err.contains("no working silent mode"), err);
        assertTrue(err.contains("can-core/CLAUDE.md"), err);
        assertTrue(err.contains("Nothing was transmitted and no capture file was created."), err);
        assertFalse(Files.exists(out));
        assertEquals(List.of(), adapter.sentFrames());
        assertEquals(List.of(), adapter.openEvents());
    }

    @Test
    void otherOpenFailuresStillSayNothingWasTransmitted() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        adapter.failNextOpen(() -> new CanAdapterException("port busy"));
        TestEnv t = new TestEnv(time, List.of(TestEnv.CANABLE), path -> adapter);

        assertEquals(1, t.run("can-log", "--output", dir.resolve("local/x.log").toString()));
        assertTrue(t.err().contains("port busy"), t.err());
        assertFalse(t.err().contains("silent mode"), t.err());
        assertTrue(t.err().contains("Nothing was transmitted"), t.err());
    }

    @Test
    void opensTheAdapterInListenOnlyAtTheRequestedBitrate() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        TestEnv t = new TestEnv(time, List.of(), path -> adapter);

        int code = t.run("can-log", "--port", "/dev/cu.usbmodem9", "--bitrate", "250k", "--duration", "1",
                "--output", dir.resolve("local/x.log").toString());

        assertEquals(0, code, t.err());
        assertEquals(List.of(new SimulatedAdapter.OpenEvent(Bitrate.KBIT_250, AdapterMode.LISTEN_ONLY)),
                adapter.openEvents());
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    void missingPortWithNoCanableIsAUsageError() {
        TestEnv t = new TestEnv(FakeTimeSource.autoAdvancing(), List.of(TestEnv.ARDUINO));
        assertEquals(2, t.run("can-log"));
        assertTrue(t.err().contains("no CANable found"), t.err());
        assertTrue(t.err().contains("Usage:"), t.err());
    }

    @Test
    void badBitrateIsAUsageError() {
        TestEnv t = new TestEnv(FakeTimeSource.autoAdvancing(), List.of(TestEnv.CANABLE));
        assertEquals(2, t.run("can-log", "--bitrate", "800k"));
        assertTrue(t.err().contains("unsupported bitrate '800k'"), t.err());
    }

    @Test
    void existingOutputIsNeverOverwritten() throws IOException {
        Path out = Files.writeString(dir.resolve("old.log"), "precious\n");
        TestEnv t = new TestEnv(FakeTimeSource.autoAdvancing(), List.of(TestEnv.CANABLE));
        assertEquals(2, t.run("can-log", "--output", out.toString()));
        assertTrue(t.err().contains("already exists"), t.err());
        assertEquals("precious\n", Files.readString(out));
    }

    @Test
    void warnsWhenTheOutputIsOutsideLocal() {
        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        TestEnv t = new TestEnv(time, List.of(TestEnv.CANABLE), path -> new SimulatedAdapter(time));
        assertEquals(0, t.run("can-log", "--duration", "1", "--output", dir.resolve("x.log").toString()));
        assertTrue(t.err().contains("outside local/"), t.err());
    }

    @Test
    void helpGoesToStdout() {
        TestEnv t = new TestEnv(FakeTimeSource.autoAdvancing(), List.of());
        assertEquals(0, t.run("can-log", "--help"));
        assertTrue(t.out().contains("listen-only mode only"), t.out());
    }

    @Test
    void mainRejectsUnknownCommands() {
        TestEnv t = new TestEnv(FakeTimeSource.autoAdvancing(), List.of());
        assertEquals(2, t.run("send-frame"));
        assertTrue(t.err().contains("unknown command 'send-frame'"), t.err());
        assertEquals(2, new TestEnv(FakeTimeSource.autoAdvancing(), List.of()).run());
    }
}
