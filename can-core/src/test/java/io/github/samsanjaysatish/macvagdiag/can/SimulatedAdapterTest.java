package io.github.samsanjaysatish.macvagdiag.can;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulatedAdapterTest {

    private static final CanFrame REQUEST = CanFrame.standardHex(0x7E0, "02 10 03 00 00 00 00 00");
    private static final CanFrame RESPONSE = CanFrame.standardHex(0x7E8, "06 50 03 00 32 01 F4 00");

    private final FakeTimeSource time = new FakeTimeSource();

    private SimulatedAdapter openAdapter(SimulatedAdapter.Responder responder) {
        SimulatedAdapter adapter = new SimulatedAdapter(time, responder);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        return adapter;
    }

    private CompletableFuture<Optional<TimestampedFrame>> receiveAsync(CanAdapter adapter, Duration timeout) {
        CompletableFuture<Optional<TimestampedFrame>> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                result.complete(adapter.receive(timeout));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Test
    @DisplayName("10 03 request on 7E0 is answered with 50 03 on 7E8")
    void responderReplies() throws Exception {
        SimulatedAdapter adapter = openAdapter(sent ->
                sent.id() == 0x7E0 ? List.of(Reply.now(RESPONSE)) : List.of());
        adapter.send(REQUEST);
        TimestampedFrame reply = adapter.receive(Duration.ZERO).orElseThrow();
        assertEquals(RESPONSE, reply.frame());
        assertEquals(0, reply.monotonicNanos());
        assertEquals(FakeTimeSource.DEFAULT_WALL_START, reply.wallClock());
    }

    @Test
    void responderCanReplyWithSeveralFramesInOrder() throws Exception {
        CanFrame first = CanFrame.standardHex(0x7E8, "10 14 59 02 FF 01 23 45");
        CanFrame second = CanFrame.standardHex(0x7E8, "21 08 00 00 00 00 00 00");
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.now(first), Reply.now(second)));
        adapter.send(REQUEST);
        assertEquals(first, adapter.receive(Duration.ZERO).orElseThrow().frame());
        assertEquals(second, adapter.receive(Duration.ZERO).orElseThrow().frame());
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO));
    }

    @Test
    void recordsEverySentFrame() {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        CanFrame keepAlive = CanFrame.standard(0x740, 0xA3);
        adapter.send(REQUEST);
        adapter.send(keepAlive);
        assertEquals(List.of(REQUEST, keepAlive), adapter.sentFrames());
        adapter.clearSentFrames();
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    void recordsOpenEvents() {
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        adapter.open(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY);
        adapter.close();
        assertEquals(List.of(new SimulatedAdapter.OpenEvent(Bitrate.KBIT_500, AdapterMode.LISTEN_ONLY)),
                adapter.openEvents());
    }

    @Test
    @DisplayName("a reply delayed 50 ms is released only when the fake clock reaches 50 ms")
    void delayedReply() throws Exception {
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.after(Duration.ofMillis(50), RESPONSE)));
        adapter.send(REQUEST);
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO), "not yet due");
        assertEquals(1, adapter.pendingFrames());
        time.advanceMillis(49);
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO));
        time.advanceMillis(1);
        TimestampedFrame reply = adapter.receive(Duration.ZERO).orElseThrow();
        assertEquals(RESPONSE, reply.frame());
        assertEquals(Duration.ofMillis(50).toNanos(), reply.monotonicNanos());
    }

    @Test
    @DisplayName("receive(100 ms) blocked in another thread gets a reply delayed 50 ms once the clock advances")
    void blockedReceiveWakesWhenDelayedReplyIsDue() throws Exception {
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.after(Duration.ofMillis(50), RESPONSE)));
        adapter.send(REQUEST);
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(adapter, Duration.ofMillis(100));
        time.awaitWaiters(1);
        time.advanceMillis(50);
        assertEquals(RESPONSE, result.get().orElseThrow().frame());
    }

    @Test
    @DisplayName("on an auto-advancing clock, a reply delayed 50 ms arrives within receive(100 ms) instantly")
    void delayedReplyOnAutoAdvancingClock() throws Exception {
        FakeTimeSource auto = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(auto, sent -> List.of(Reply.after(Duration.ofMillis(50), RESPONSE)));
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.send(REQUEST);
        TimestampedFrame reply = adapter.receive(Duration.ofMillis(100)).orElseThrow();
        assertEquals(RESPONSE, reply.frame());
        assertEquals(Duration.ofMillis(50), auto.elapsed());
    }

    @Test
    @DisplayName("receive(500 ms) with no reply times out at exactly 500 ms of fake time, without sleeping")
    void timeoutOnAutoAdvancingClock() throws Exception {
        FakeTimeSource auto = FakeTimeSource.autoAdvancing();
        SimulatedAdapter adapter = new SimulatedAdapter(auto);
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        adapter.send(REQUEST);
        assertEquals(Optional.empty(), adapter.receive(Duration.ofMillis(500)));
        assertEquals(Duration.ofMillis(500), auto.elapsed());
    }

    @Test
    void timeoutOnManualClock() throws Exception {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(adapter, Duration.ofMillis(500));
        time.awaitWaiters(1);
        time.advanceMillis(500);
        assertEquals(Optional.empty(), result.get());
    }

    @Test
    void injectedFrameWakesBlockedReceiver() throws Exception {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(adapter, Duration.ofSeconds(5));
        time.awaitWaiters(1);
        adapter.inject(RESPONSE);
        assertEquals(RESPONSE, result.get().orElseThrow().frame());
        assertEquals(0, time.nanoTime(), "no fake time had to pass");
    }

    @Test
    void injectAfterDelay() throws Exception {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        adapter.injectAfter(Duration.ofMillis(10), RESPONSE);
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO));
        time.advanceMillis(10);
        assertEquals(RESPONSE, adapter.receive(Duration.ZERO).orElseThrow().frame());
    }

    @Test
    void dropNextReplies() throws Exception {
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.now(RESPONSE)));
        adapter.dropNextReplies(1);
        adapter.send(REQUEST);
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO), "first reply dropped");
        adapter.send(REQUEST);
        assertEquals(RESPONSE, adapter.receive(Duration.ZERO).orElseThrow().frame(), "second gets through");
        assertEquals(List.of(REQUEST, REQUEST), adapter.sentFrames(), "the requests themselves were sent");
    }

    @Test
    void dropRepliesWhere() throws Exception {
        CanFrame other = CanFrame.standard(0x7E9, 0x01);
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.now(RESPONSE), Reply.now(other)));
        adapter.dropRepliesWhere(frame -> frame.id() == 0x7E8);
        adapter.send(REQUEST);
        assertEquals(other, adapter.receive(Duration.ZERO).orElseThrow().frame());
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO));
    }

    @Test
    void injectedSendError() throws Exception {
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.now(RESPONSE)));
        adapter.failNextSends(1, () -> new CanAdapterException("bus off"));
        CanAdapterException e = assertThrows(CanAdapterException.class, () -> adapter.send(REQUEST));
        assertEquals("bus off", e.getMessage());
        assertEquals(List.of(), adapter.sentFrames());
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO), "the responder did not run");
        adapter.send(REQUEST);
        assertEquals(List.of(REQUEST), adapter.sentFrames());
    }

    @Test
    void injectedOpenError() {
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        adapter.failNextOpen(() -> new CanAdapterException("port busy"));
        assertThrows(CanAdapterException.class, () -> adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL));
        assertFalse(adapter.isOpen());
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertTrue(adapter.isOpen());
    }

    @Test
    void injectedReceiveFailureWakesBlockedReceiver() throws Exception {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(adapter, Duration.ofSeconds(5));
        time.awaitWaiters(1);
        adapter.failReceive(new CanAdapterException("unplugged"));
        ExecutionException e = assertThrows(ExecutionException.class, result::get);
        assertInstanceOf(CanAdapterException.class, e.getCause());
        assertThrows(CanAdapterException.class, () -> adapter.receive(Duration.ZERO));
    }

    @Test
    void closeWakesBlockedReceiver() throws Exception {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        CompletableFuture<Optional<TimestampedFrame>> result = receiveAsync(adapter, Duration.ofSeconds(5));
        time.awaitWaiters(1);
        adapter.close();
        ExecutionException e = assertThrows(ExecutionException.class, result::get);
        assertInstanceOf(CanAdapterException.class, e.getCause());
    }

    @Test
    void reopenStartsWithAnEmptyQueue() throws Exception {
        SimulatedAdapter adapter = openAdapter(sent -> List.of(Reply.now(RESPONSE)));
        adapter.send(REQUEST);
        adapter.close();
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
        assertEquals(Optional.empty(), adapter.receive(Duration.ZERO));
    }

    @Test
    void notOpen() {
        SimulatedAdapter adapter = new SimulatedAdapter(time);
        assertThrows(CanAdapterException.class, () -> adapter.send(REQUEST));
        assertThrows(CanAdapterException.class, () -> adapter.receive(Duration.ZERO));
        assertThrows(CanAdapterException.class, () -> adapter.inject(RESPONSE));
    }

    @Test
    void negativeTimeoutRejected() {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        assertThrows(IllegalArgumentException.class, () -> adapter.receive(Duration.ofMillis(-1)));
    }

    @Test
    void hugeTimeoutDoesNotOverflow() throws Exception {
        SimulatedAdapter adapter = openAdapter(SimulatedAdapter.Responder.silent());
        adapter.inject(RESPONSE);
        assertEquals(RESPONSE, adapter.receive(Duration.ofSeconds(Long.MAX_VALUE)).orElseThrow().frame());
    }
}
