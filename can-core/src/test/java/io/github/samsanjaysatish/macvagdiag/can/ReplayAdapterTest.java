package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.ReplayAdapter.Pacing;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReplayAdapterTest {

    /**
     * A hand-written TP 2.0 channel setup to the engine (logical address 01), as VCDS would do it:
     * a broadcast frame before the tester starts, then 200 -> 201 setup, then 740 -> 300 parameters.
     */
    private static final String CAPTURE = """
            # date: 2026-01-01
            # bitrate: 500000
            (1729600000.000000) can0 5A0#0102
            (1729600000.100000) can0 200#01C00010000301
            (1729600000.110000) can0 201#00D00003400701
            (1729600000.200000) can0 740#A00F8AFF32FF
            (1729600000.205000) can0 300#A10F8AFF4AFF
            """;

    private static final CanFrame BROADCAST = CanFrame.standard(0x5A0, 0x01, 0x02);
    private static final CanFrame SETUP = CanFrame.standardHex(0x200, "01 C0 00 10 00 03 01");
    private static final CanFrame SETUP_REPLY = CanFrame.standardHex(0x201, "00 D0 00 03 40 07 01");
    private static final CanFrame PARAMS = CanFrame.standardHex(0x740, "A0 0F 8A FF 32 FF");
    private static final CanFrame PARAMS_REPLY = CanFrame.standardHex(0x300, "A1 0F 8A FF 4A FF");

    private static final Predicate<CanFrame> FROM_TESTER = f -> f.id() == 0x200 || f.id() == 0x740;

    private final FakeTimeSource time = new FakeTimeSource();

    private static Capture capture() throws IOException {
        return CaptureReader.read(new StringReader(CAPTURE));
    }

    private static List<CanFrame> drain(CanAdapter adapter) throws InterruptedException {
        List<CanFrame> frames = new ArrayList<>();
        Optional<TimestampedFrame> f;
        while ((f = adapter.receive(Duration.ZERO)).isPresent()) {
            frames.add(f.get().frame());
        }
        return frames;
    }

    // --- passive ---

    @Test
    void passivePlaysEveryFrameInOrder() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.IMMEDIATE, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertEquals(List.of(BROADCAST, SETUP, SETUP_REPLY, PARAMS, PARAMS_REPLY), drain(adapter));
        assertEquals(0, adapter.remainingFrames());
    }

    @Test
    void passiveFramesCarryTheRecordedWallClock() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.IMMEDIATE, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        adapter.receive(Duration.ZERO);
        TimestampedFrame second = adapter.receive(Duration.ZERO).orElseThrow();
        assertEquals(Instant.ofEpochSecond(1_729_600_000L, 100_000_000), second.wallClock());
    }

    @Test
    @DisplayName("passive with recorded pacing releases 200 [7] after 100 ms and 201 [7] after 110 ms of fake time")
    void passiveRecordedPacing() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.RECORDED, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertEquals(List.of(BROADCAST), drain(adapter));
        time.advanceMillis(99);
        assertEquals(List.of(), drain(adapter));
        time.advanceMillis(1);
        assertEquals(List.of(SETUP), drain(adapter));
        time.advanceMillis(10);
        TimestampedFrame reply = adapter.receive(Duration.ZERO).orElseThrow();
        assertEquals(SETUP_REPLY, reply.frame());
        assertEquals(Duration.ofMillis(110).toNanos(), reply.monotonicNanos());
    }

    @Test
    void passiveRecordedPacingOnAutoAdvancingClock() throws Exception {
        FakeTimeSource auto = FakeTimeSource.autoAdvancing();
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.RECORDED, auto);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        List<CanFrame> frames = new ArrayList<>();
        Optional<TimestampedFrame> f;
        while ((f = adapter.receive(Duration.ofSeconds(1))).isPresent()) {
            frames.add(f.get().frame());
        }
        assertEquals(List.of(BROADCAST, SETUP, SETUP_REPLY, PARAMS, PARAMS_REPLY), frames);
        // last frame at 205 ms, then one more 1 s wait that timed out
        assertEquals(Duration.ofMillis(1205), auto.elapsed());
    }

    @Test
    @DisplayName("an exhausted replay times out like a quiet bus")
    void passiveExhaustedTimesOut() throws Exception {
        FakeTimeSource auto = FakeTimeSource.autoAdvancing();
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.IMMEDIATE, auto);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        drain(adapter);
        assertEquals(Optional.empty(), adapter.receive(Duration.ofMillis(250)));
        assertEquals(Duration.ofMillis(250), auto.elapsed());
    }

    @Test
    void passiveRefusesSendInNormalMode() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.IMMEDIATE, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertThrows(CanAdapterException.class, () -> adapter.send(SETUP));
    }

    @Test
    void reopenRestartsTheReplay() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.passive(capture(), Pacing.IMMEDIATE, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        drain(adapter);
        adapter.close();
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        assertEquals(5, drain(adapter).size());
    }

    // --- interactive ---

    @Test
    @DisplayName("interactive: frames before the first tester frame (5A0) are released on open")
    void interactivePreamble() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertEquals(List.of(BROADCAST), drain(adapter));
        assertEquals(Optional.of(SETUP), adapter.nextExpectedFrame());
    }

    @Test
    @DisplayName("interactive: send 200 [7] 01 C0 ... releases 201 [7] 00 D0 ..., then 740 releases 300")
    void interactiveMatchingSendReleasesReplies() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        drain(adapter);

        adapter.send(SETUP);
        assertEquals(List.of(SETUP_REPLY), drain(adapter));
        adapter.send(PARAMS);
        assertEquals(List.of(PARAMS_REPLY), drain(adapter));

        assertEquals(List.of(SETUP, PARAMS), adapter.sentFrames());
        assertEquals(0, adapter.remainingFrames());
        assertEquals(Optional.empty(), adapter.nextExpectedFrame());
    }

    @Test
    void interactiveRecordedPacingDelaysRepliesByTheRecordedGap() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, FrameMatcher.exact(),
                Pacing.RECORDED, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        drain(adapter);
        adapter.send(SETUP);
        assertEquals(List.of(), drain(adapter), "reply recorded 10 ms after the request");
        time.advanceMillis(10);
        assertEquals(List.of(SETUP_REPLY), drain(adapter));
    }

    @Test
    @DisplayName("interactive: a send that doesn't match the recorded tester frame throws and releases nothing")
    void interactiveMismatch() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        drain(adapter);
        CanFrame wrong = CanFrame.standardHex(0x200, "01 C0 00 10 00 03 02");
        ReplayMismatchException e = assertThrows(ReplayMismatchException.class, () -> adapter.send(wrong));
        assertEquals(Optional.of(SETUP), e.expected());
        assertEquals(wrong, e.sent());
        assertTrue(e.getMessage().contains("200 [7] 01 C0 00 10 00 03 01"), e.getMessage());
        assertEquals(List.of(), drain(adapter));
        assertEquals(Optional.of(SETUP), adapter.nextExpectedFrame(), "the replay did not advance");
    }

    @Test
    void interactiveSendPastTheEndThrows() throws Exception {
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.send(SETUP);
        adapter.send(PARAMS);
        ReplayMismatchException e = assertThrows(ReplayMismatchException.class, () -> adapter.send(SETUP));
        assertEquals(Optional.empty(), e.expected());
    }

    @Test
    @DisplayName("interactive: a custom matcher can ignore a field, e.g. the last byte of 200 [7]")
    void interactiveCustomMatcher() throws Exception {
        FrameMatcher ignoreLastByte = (recorded, sent) -> recorded.id() == sent.id()
                && recorded.length() == sent.length()
                && java.util.Arrays.equals(recorded.data(), 0, recorded.length() - 1, sent.data(), 0, sent.length() - 1);
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, ignoreLastByte, Pacing.IMMEDIATE, time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        drain(adapter);
        adapter.send(CanFrame.standardHex(0x200, "01 C0 00 10 00 03 09"));
        assertEquals(List.of(SETUP_REPLY), drain(adapter));
    }

    @Test
    void interactiveReceiveTimesOutWhenNothingWasSent() throws Exception {
        FakeTimeSource auto = FakeTimeSource.autoAdvancing();
        ReplayAdapter adapter = ReplayAdapter.interactive(capture(), FROM_TESTER, auto);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        drain(adapter);
        assertEquals(Optional.empty(), adapter.receive(Duration.ofMillis(300)));
        assertEquals(Duration.ofMillis(300), auto.elapsed());
    }
}
