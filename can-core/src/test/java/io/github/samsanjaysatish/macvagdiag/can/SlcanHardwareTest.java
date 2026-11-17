package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>Manual only</b> ({@code ./gradlew :can-core:hardwareTest}), with the real CANable on the patched firmware
 * {@code macvagdiag-silent-1}. Never run by Claude or CI.
 *
 * <p>Checks that the real {@link SlcanAdapter} behaves as the fakes assume: it opens, receives and closes cleanly,
 * and it can be opened again afterwards (the port was released). <b>No test here calls {@code send}</b>, in any
 * mode: that {@code LISTEN_ONLY} refuses to send is proven without hardware ({@code ListenOnlyGuaranteeTest}), and
 * proving it on the car would mean trying to put a frame on the car's bus.
 *
 * <p>The diagnostic CAN behind the gateway is silent without a tester (2026-11-13 baseline: ignition on, no VCDS,
 * 60 s, 0 frames). So the tests that need no traffic run anywhere, and the receive test runs only when
 * {@code MACVAGDIAG_EXPECT_FRAMES=1} says VCDS is talking on the bus (start a VCDS Auto-Scan while it waits).
 *
 * <p>Environment (all optional): {@code MACVAGDIAG_PORT} picks the serial port (default: the only CANable found);
 * {@code MACVAGDIAG_EXPECT_FRAMES=1} enables the receive test and skips the {@code NORMAL}-mode test.
 */
@Tag("hardware")
class SlcanHardwareTest {

    /** How long the receive test waits for the first frame, i.e. for VCDS to start talking. */
    static final Duration WAIT_FOR_TRAFFIC = Duration.ofSeconds(60);
    /** How long it keeps receiving after the first frame. */
    static final Duration RECEIVE_FOR = Duration.ofSeconds(5);
    static final Duration POLL = Duration.ofMillis(200);

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void listenOnlyOpensClosesAndReopens() throws InterruptedException {
        String port = port();
        for (int round = 1; round <= 2; round++) {
            SlcanAdapter adapter = SlcanAdapter.forPort(port);
            try {
                adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY); // passes only on macvagdiag-silent-1
                assertTrue(adapter.isOpen());
                assertEquals(Optional.of(AdapterMode.LISTEN_ONLY), adapter.mode());
                List<TimestampedFrame> frames = receiveFor(adapter, Duration.ofSeconds(2));
                System.out.printf("round %d: open in LISTEN_ONLY on %s, %d frames in 2 s%n", round, port, frames.size());
                assertNoErrors(adapter);
            } finally {
                adapter.close();
            }
            assertFalse(adapter.isOpen(), "closed after round " + round);
        }
    }

    /**
     * The patched firmware keeps the silent mode between opens, so {@code SlcanAdapter} sends {@code M0} for a
     * {@code NORMAL} open and {@code M1} for a {@code LISTEN_ONLY} one. This opens in {@code NORMAL} (sending
     * nothing), closes, and checks that a {@code LISTEN_ONLY} open still passes the firmware check afterwards.
     * In {@code NORMAL} the controller ACKs frames like any tester, so it is skipped while VCDS is on the bus.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisabledIfEnvironmentVariable(named = "MACVAGDIAG_EXPECT_FRAMES", matches = "1",
            disabledReason = "opens in NORMAL mode; not while VCDS is on the bus")
    void normalOpenThenListenOnlyAgain() throws InterruptedException {
        String port = port();
        try (SlcanAdapter adapter = SlcanAdapter.forPort(port)) {
            adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
            assertEquals(Optional.of(AdapterMode.NORMAL), adapter.mode());
            receiveFor(adapter, Duration.ofSeconds(1));
            assertNoErrors(adapter);
        }
        try (SlcanAdapter adapter = SlcanAdapter.forPort(port)) {
            adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
            assertEquals(Optional.of(AdapterMode.LISTEN_ONLY), adapter.mode());
            receiveFor(adapter, Duration.ofSeconds(1));
            assertNoErrors(adapter);
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @EnabledIfEnvironmentVariable(named = "MACVAGDIAG_EXPECT_FRAMES", matches = "1",
            disabledReason = "needs VCDS talking on the car's diagnostic CAN; set MACVAGDIAG_EXPECT_FRAMES=1")
    void receivesFramesFromTheCarInListenOnly() throws InterruptedException {
        TimeSource time = TimeSource.system();
        try (SlcanAdapter adapter = SlcanAdapter.forPort(port(), time)) {
            adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
            System.out.println("open in LISTEN_ONLY; start a VCDS Auto-Scan now (waiting up to "
                    + WAIT_FOR_TRAFFIC.toSeconds() + " s for the first frame)");

            Optional<TimestampedFrame> first = Optional.empty();
            long end = time.deadlineAfter(WAIT_FOR_TRAFFIC);
            while (first.isEmpty() && time.nanoTime() < end) {
                first = adapter.receive(POLL);
            }
            assertTrue(first.isPresent(), "no frame within " + WAIT_FOR_TRAFFIC.toSeconds()
                    + " s; is VCDS talking, and is the adapter on OBD pins 6/14?");

            List<TimestampedFrame> frames = new ArrayList<>();
            frames.add(first.get());
            frames.addAll(receiveFor(adapter, RECEIVE_FOR));
            long ids = frames.stream().mapToInt(f -> f.frame().id()).distinct().count();
            System.out.printf("received %d frames on %d IDs; first: %s%n", frames.size(), ids, first.get().frame());

            assertTrue(frames.stream().noneMatch(f -> f.frame().isExtended()),
                    "VW diagnostic CAN uses 11-bit IDs only (2026-11-13 capture)");
            assertNoErrors(adapter);
        }
    }

    private static List<TimestampedFrame> receiveFor(SlcanAdapter adapter, Duration duration)
            throws InterruptedException {
        TimeSource time = TimeSource.system();
        List<TimestampedFrame> frames = new ArrayList<>();
        long end = time.deadlineAfter(duration);
        while (time.nanoTime() < end) {
            adapter.receive(POLL).ifPresent(frames::add);
        }
        return frames;
    }

    private static void assertNoErrors(SlcanAdapter adapter) {
        assertEquals(0, adapter.decodeErrorCount(), "slcan lines that didn't decode");
        assertEquals(0, adapter.errorReplyCount(), "BEL error replies from the adapter");
        assertEquals(0, adapter.droppedFrameCount(), "frames dropped from a full receive queue");
    }

    private static String port() {
        String configured = System.getenv("MACVAGDIAG_PORT");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        List<SlcanPortDiscovery.Candidate> canables = SlcanPortDiscovery.candidates().stream()
                .filter(SlcanPortDiscovery.Candidate::isCanable)
                .toList();
        assertEquals(1, canables.size(), "expected exactly one CANable, found " + canables
                + "; set MACVAGDIAG_PORT to choose one");
        System.out.println("using " + canables.getFirst());
        return canables.getFirst().path();
    }
}
