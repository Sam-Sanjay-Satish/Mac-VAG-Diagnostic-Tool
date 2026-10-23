package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.samsanjaysatish.macvagdiag.can.Capture;
import io.github.samsanjaysatish.macvagdiag.can.CaptureReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Manual only ({@code ./gradlew :tools:hardwareTest}), with the CANable on the running car's diagnostic
 * CAN: logs 10 seconds in listen-only mode through the real program. Never run by Claude or CI.
 *
 * <p><b>Expected to fail until silent mode is solved:</b> SlcanAdapter refuses LISTEN_ONLY with the stock
 * firmware (see can-core/CLAUDE.md), so can-log exits 1 at open without transmitting.
 */
@Tag("hardware")
class CanLogHardwareTest {

    @TempDir
    Path dir; // deleted afterwards: the capture contains the VIN

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void logsTenSecondsFromTheCarInListenOnly() throws IOException {
        Path out = dir.resolve("hardware-test.log");
        int code = Main.run(new String[] {"can-log", "--duration", "10", "--output", out.toString(),
                "--note", "hardware test"}, Environment.system());
        assertEquals(0, code);
        Capture capture = CaptureReader.read(out);
        assertEquals("LISTEN_ONLY", capture.metadata().get("mode"));
        assertFalse(capture.frames().isEmpty(), "the running car's bus is never silent for 10 s");
    }
}
