package io.github.samsanjaysatish.macvagdiag.transport;

import static io.github.samsanjaysatish.macvagdiag.transport.ScriptedBus.reply;
import static io.github.samsanjaysatish.macvagdiag.transport.ScriptedBus.replyAfter;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VW TP 2.0 against hand-written frames. All byte layouts are UNCONFIRMED (from memory) until the capture;
 * when it corrects them, these frames change with the constants in {@link Tp20Channel}.
 */
class Tp20ChannelTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final FakeTimeSource time = FakeTimeSource.autoAdvancing();
    private final ScriptedBus script = new ScriptedBus(time);
    private final SimulatedAdapter adapter = new SimulatedAdapter(time, script);

    @BeforeEach
    void openAdapter() {
        adapter.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
    }

    @AfterEach
    void closeAdapter() {
        adapter.close();
    }

    /** {@code 200 01 C0 00 10 00 03 01} → {@code 201 00 D0 00 03 40 07 01}, then {@code A0} → {@code A1}. */
    private void expectSetup(String a1) {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D0 00 03 40 07 01"))
                .expect(0x740, "A0 0F 8A FF 32 FF", reply(0x300, a1));
    }

    private Tp20Channel connect() throws InterruptedException {
        return Tp20Channel.connect(new BusGate(adapter, time), 0x01);
    }

    private Tp20Channel connected() throws InterruptedException {
        expectSetup("A1 0F 8A FF 4A FF");
        return connect();
    }

    private static Response.Received received(String hex) {
        return new Response.Received(Hex.parse(hex));
    }

    // ------------------------------------------------------------------ setup and parameters

    @Test
    @DisplayName("setup 200 01 C0 00 10 00 03 01 → 00 D0 00 03 40 07 01, then A0 0F 8A FF 32 FF → A1 0F 8A FF 4A FF")
    void setupAndTimingExchange() throws Exception {
        Tp20Channel ch = connected();

        script.assertComplete();
        assertEquals(0x740, ch.txId());
        assertEquals(0x300, ch.rxId());
        assertEquals(15, ch.blockSize());
        assertEquals(Duration.ofMillis(100), ch.ackTimeout(), "T1 8A = 10 x 10 ms");
        assertEquals(Duration.ofMillis(10), ch.packetInterval(), "T3 4A = 10 x 1 ms");
        assertTrue(ch.isOpen());
    }

    @Test
    @DisplayName("setup with no reply times out after 200 ms: NO_RESPONSE")
    void setupTimeout() {
        script.expect(0x200, "01 C0 00 10 00 03 01");
        TransportException e = assertThrows(TransportException.class, this::connect);
        assertEquals(TransportException.Reason.NO_RESPONSE, e.reason());
        assertEquals(Duration.ofMillis(200), time.elapsed());
        script.assertComplete();
    }

    @Test
    @DisplayName("setup replies addressed to another tester are ignored")
    void setupReplyForSomeoneElse() {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "05 D0 00 03 40 07 01"), reply(0x202, "00 D0 00 03 40 07 01"));
        assertEquals(TransportException.Reason.NO_RESPONSE, assertThrows(TransportException.class, this::connect).reason());
    }

    @Test
    @DisplayName("negative setup reply 00 D6: REFUSED_BY_MODULE")
    void setupRefused() {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D6"));
        assertEquals(TransportException.Reason.REFUSED_BY_MODULE,
                assertThrows(TransportException.class, this::connect).reason());
    }

    @Test
    @DisplayName("setup negotiating tester tx 123 is refused and nothing is sent on 123")
    void negotiatedIdOutsideRange() {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D0 00 03 23 01 01"));
        assertEquals(TransportException.Reason.DISALLOWED_CAN_ID,
                assertThrows(TransportException.class, this::connect).reason());
        assertEquals(List.of("200 [7] 01 C0 00 10 00 03 01"), adapter.sentFrames().stream().map(Object::toString).toList());
    }

    @Test
    @DisplayName("setup negotiating the UDS ID 7E0 as TP 2.0 tx is refused")
    void negotiatedUdsIdRefused() {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D0 00 03 E0 07 01"));
        assertEquals(TransportException.Reason.DISALLOWED_CAN_ID,
                assertThrows(TransportException.class, this::connect).reason());
        assertEquals(1, adapter.sentFrames().size());
    }

    @Test
    @DisplayName("setup reply with the invalid flag on an ID: PROTOCOL_ERROR")
    void setupInvalidId() {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D0 00 13 40 07 01"));
        assertEquals(TransportException.Reason.PROTOCOL_ERROR,
                assertThrows(TransportException.class, this::connect).reason());
    }

    @Test
    @DisplayName("no A1 after A0: NO_RESPONSE, and the channel is dropped with A8")
    void timingReplyTimeout() {
        script.expect(0x200, "01 C0 00 10 00 03 01", reply(0x201, "00 D0 00 03 40 07 01"))
                .expect(0x740, "A0 0F 8A FF 32 FF")
                .expect(0x740, "A8");
        assertEquals(TransportException.Reason.NO_RESPONSE, assertThrows(TransportException.class, this::connect).reason());
        script.assertComplete();
    }

    @Test
    @DisplayName("time bytes: 8A = 100 ms, 32 = 5 ms, 4A = 10 ms, C1 = 100 ms, 00 = 0")
    void decodeTime() {
        assertEquals(Duration.ofMillis(100), Tp20Channel.decodeTime(0x8A));
        assertEquals(Duration.ofMillis(5), Tp20Channel.decodeTime(0x32));
        assertEquals(Duration.ofMillis(10), Tp20Channel.decodeTime(0x4A));
        assertEquals(Duration.ofMillis(100), Tp20Channel.decodeTime(0xC1));
        assertEquals(Duration.ofMillis(6300), Tp20Channel.decodeTime(0xFF));
        assertEquals(Duration.ZERO, Tp20Channel.decodeTime(0x00));
        assertEquals(Duration.ofNanos(100_000), Tp20Channel.decodeTime(0x01));
    }

    @Test
    @DisplayName("ID fields: 00 03 = 300, 40 07 = 740, 00 10 = invalid")
    void decodeId() {
        assertEquals(0x300, Tp20Channel.decodeId(0x00, 0x03));
        assertEquals(0x740, Tp20Channel.decodeId(0x40, 0x07));
        assertEquals(-1, Tp20Channel.decodeId(0x00, 0x10));
    }

    @Test
    @DisplayName("segmenting: first packet = 2-byte length + 5 bytes, then 7 per packet")
    void segment() {
        List<byte[]> packets = Tp20Channel.segment(Hex.parse("01 02 03 04 05 06 07 08 09 0A 0B 0C 0D"));
        assertEquals(List.of("00 0D 01 02 03 04 05", "06 07 08 09 0A 0B 0C", "0D"),
                packets.stream().map(Hex::format).toList());
        assertEquals(List.of("00 02 10 89"), Tp20Channel.segment(Hex.parse("10 89")).stream().map(Hex::format).toList());
        assertEquals(List.of("00 05 01 02 03 04 05"),
                Tp20Channel.segment(Hex.parse("01 02 03 04 05")).stream().map(Hex::format).toList());
    }

    // ------------------------------------------------------------------ messages

    @Test
    @DisplayName("single packet: 10 00 02 10 89 → B1, 10 00 02 50 89 → tester ACK B1")
    void singlePacketExchange() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "10 00 02 50 89"))
                .expect(0x740, "B1");

        assertEquals(received("50 89"), ch.exchange(Hex.parse("10 89"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("multi-packet response 20 00 16 ... / 21 / 22 / 13 is reassembled and ACKed with B4")
    void multiPacketResponse() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 1A 9B", reply(0x300, "B1"),
                        reply(0x300, "20 00 16 5A 9B 01 02 03"),
                        reply(0x300, "21 04 05 06 07 08 09 0A"),
                        reply(0x300, "22 0B 0C 0D 0E 0F 10 11"),
                        reply(0x300, "13 12 13 14"))
                .expect(0x740, "B4");

        assertEquals(received("5A 9B 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14"),
                ch.exchange(Hex.parse("1A 9B"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("module ACK request mid-message (0x opcode) is ACKed before the rest arrives")
    void moduleRequestsAckMidMessage() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 1A 9B", reply(0x300, "B1"),
                        reply(0x300, "00 00 09 5A 9B 01 02 03"))
                .expect(0x740, "B1", reply(0x300, "11 04 05 06 07"))
                .expect(0x740, "B2");

        assertEquals(received("5A 9B 01 02 03 04 05 06 07"), ch.exchange(Hex.parse("1A 9B"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("multi-packet request with module block size 2: 20 / 01 → B2, 12 → B3; packets 10 ms apart (T3)")
    void multiPacketRequestBlockSizeAndPacing() throws Exception {
        expectSetup("A1 02 8A FF 4A FF");
        Tp20Channel ch = connect();
        assertEquals(2, ch.blockSize());
        script.expect(0x740, "20 00 0F 01 02 03 04 05")
                .expect(0x740, "01 06 07 08 09 0A 0B 0C", reply(0x300, "B2"))
                .expect(0x740, "12 0D 0E 0F", reply(0x300, "B3"), reply(0x300, "10 00 02 7E 00"))
                .expect(0x740, "B1");

        Response r = ch.exchange(Hex.parse("01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F"), TIMEOUT);

        assertEquals(received("7E 00"), r);
        script.assertComplete();
        List<Long> t = script.sendTimes(); // 0 setup, 1 A0, 2..4 data packets, 5 ACK
        assertEquals(Duration.ofMillis(10).toNanos(), t.get(3) - t.get(2));
        assertEquals(Duration.ofMillis(10).toNanos(), t.get(4) - t.get(3));
    }

    @Test
    @DisplayName("sequence numbers wrap F → 0 in both directions, across messages and inside one")
    void sequenceWraparound() throws Exception {
        Tp20Channel ch = connected();
        for (int i = 0; i < 14; i++) {
            int s = i & 0x0F;
            int next = (i + 1) & 0x0F;
            script.expect(0x740, String.format("1%X 00 01 3E", s), reply(0x300, String.format("B%X", next)),
                            reply(0x300, String.format("1%X 00 01 7E", s)))
                    .expect(0x740, String.format("B%X", next));
        }
        // Tester at E, module at E. Request packets E, F, 0 and a response with packets E, F, 0, 1.
        script.expect(0x740, "2E 00 12 01 02 03 04 05")
                .expect(0x740, "2F 06 07 08 09 0A 0B 0C")
                .expect(0x740, "10 0D 0E 0F 10 11 12", reply(0x300, "B1"),
                        reply(0x300, "2E 00 14 41 42 43 44 45"),
                        reply(0x300, "2F 46 47 48 49 4A 4B 4C"),
                        reply(0x300, "20 4D 4E 4F 50 51 52 53"),
                        reply(0x300, "11 54"))
                .expect(0x740, "B2")
                .expect(0x740, "11 00 01 3E", reply(0x300, "B2"), reply(0x300, "12 00 01 7E"))
                .expect(0x740, "B3");

        for (int i = 0; i < 14; i++) {
            assertEquals(received("7E"), ch.exchange(Hex.parse("3E"), TIMEOUT), "exchange " + i);
        }
        assertEquals(received("41 42 43 44 45 46 47 48 49 4A 4B 4C 4D 4E 4F 50 51 52 53 54"),
                ch.exchange(Hex.parse("01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12"), TIMEOUT));
        assertEquals(received("7E"), ch.exchange(Hex.parse("3E"), TIMEOUT));
        script.assertComplete();
    }

    // ------------------------------------------------------------------ ACK handling

    @Test
    @DisplayName("not-ready ACK 91 then ready B1: the request completes")
    void notReadyThenReady() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "91"), replyAfter(Duration.ofMillis(50), 0x300, "B1"),
                        replyAfter(Duration.ofMillis(60), 0x300, "10 00 02 50 89"))
                .expect(0x740, "B1");

        assertEquals(received("50 89"), ch.exchange(Hex.parse("10 89"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("module stays not ready (91 x 11): TRANSPORT_TIMEOUT, channel dropped with A8")
    void notReadyForever() throws Exception {
        Tp20Channel ch = connected();
        SimulatedAdapter.Reply[] notReady = new SimulatedAdapter.Reply[Tp20Channel.MAX_NOT_READY_ACKS + 1];
        for (int i = 0; i < notReady.length; i++) {
            notReady[i] = reply(0x300, "91");
        }
        script.expect(0x740, "10 00 02 10 89", notReady).expect(0x740, "A8");

        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));
        assertEquals(Response.Failed.Kind.TRANSPORT_TIMEOUT, f.kind());
        assertFalse(ch.isOpen());
        script.assertComplete();
    }

    @Test
    @DisplayName("no ACK: the packet is resent twice (same sequence number), then TRANSPORT_TIMEOUT and A8")
    void ackTimeoutRetriesCapped() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89").expect(0x740, "10 00 02 10 89").expect(0x740, "10 00 02 10 89")
                .expect(0x740, "A8");
        Duration before = time.elapsed();

        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));

        assertEquals(Response.Failed.Kind.TRANSPORT_TIMEOUT, f.kind());
        assertEquals(Duration.ofMillis(300), time.elapsed().minus(before), "3 x T1 (100 ms)");
        assertFalse(ch.isOpen());
        assertEquals(f, ch.exchange(Hex.parse("10 89"), TIMEOUT), "later calls report the same failure");
        script.assertComplete();
    }

    @Test
    @DisplayName("no ACK the first time, ACK after one resend: the request completes")
    void ackAfterRetry() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89")
                .expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "10 00 02 50 89"))
                .expect(0x740, "B1");
        assertEquals(received("50 89"), ch.exchange(Hex.parse("10 89"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("ACK with the wrong sequence (B5 for B1): PROTOCOL_ERROR, channel dropped")
    void wrongAckSequence() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B5")).expect(0x740, "A8");
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR, f.kind());
        assertFalse(ch.isOpen());
        script.assertComplete();
    }

    // ------------------------------------------------------------------ receive errors

    @Test
    @DisplayName("response packet with the wrong sequence (13 for 10): PROTOCOL_ERROR, channel dropped")
    void wrongReceiveSequence() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "13 00 02 50 89"))
                .expect(0x740, "A8");
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR, f.kind());
        assertFalse(ch.isOpen());
        script.assertComplete();
    }

    @Test
    @DisplayName("last packet before the announced length (00 05, 2 bytes): PROTOCOL_ERROR")
    void lengthMismatch() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "10 00 05 50 89"))
                .expect(0x740, "B1")
                .expect(0x740, "A8");
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR, f.kind());
        script.assertComplete();
    }

    @Test
    @DisplayName("frames on other IDs are ignored")
    void otherIdsIgnored() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x301, "B1"), reply(0x300, "B1"), reply(0x7E8, "10 00 02 7F 10"),
                        reply(0x300, "10 00 02 50 89"))
                .expect(0x740, "B1");
        assertEquals(received("50 89"), ch.exchange(Hex.parse("10 89"), TIMEOUT));
        script.assertComplete();
    }

    // ------------------------------------------------------------------ timeouts and pending responses

    @Test
    @DisplayName("no response within the caller's timeout: TimedOut after exactly 1 s, channel still open")
    void responseTimeout() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"));
        Duration before = time.elapsed();

        assertEquals(new Response.TimedOut(TIMEOUT), ch.exchange(Hex.parse("10 89"), TIMEOUT));

        assertEquals(TIMEOUT, time.elapsed().minus(before));
        assertTrue(ch.isOpen());
        script.assertComplete();
    }

    @Test
    @DisplayName("7F 1A 78 (response pending), then awaitResponse gets the real answer 2 s later without resending")
    void responsePending() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 1A 9B", reply(0x300, "B1"), reply(0x300, "10 00 03 7F 1A 78"),
                        replyAfter(Duration.ofSeconds(2), 0x300, "11 00 02 5A 9B"))
                .expect(0x740, "B1")
                .expect(0x740, "B2");

        assertEquals(received("7F 1A 78"), ch.exchange(Hex.parse("1A 9B"), TIMEOUT));
        Duration before = time.elapsed();
        assertEquals(received("5A 9B"), ch.awaitResponse(Duration.ofSeconds(5)));
        assertEquals(Duration.ofSeconds(2), time.elapsed().minus(before));
        script.assertComplete();
    }

    @Test
    @DisplayName("a late response to a timed-out request is ACKed and discarded, not returned for the next request")
    void staleResponseDiscarded() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), replyAfter(Duration.ofSeconds(1), 0x300, "10 00 02 50 89"))
                .expect(0x740, "B1")
                .expect(0x740, "11 00 02 1A 9B", reply(0x300, "B2"), reply(0x300, "11 00 02 5A 9B"))
                .expect(0x740, "B2");

        assertInstanceOf(Response.TimedOut.class, ch.exchange(Hex.parse("10 89"), Duration.ofMillis(100)));
        time.advance(Duration.ofSeconds(2));
        assertEquals(received("5A 9B"), ch.exchange(Hex.parse("1A 9B"), TIMEOUT));
        script.assertComplete();
    }

    // ------------------------------------------------------------------ keep-alive and disconnect

    @Test
    @DisplayName("keep-alive A3 → A1 0F 8A FF 4A FF: alive")
    void keepAlive() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "A3", reply(0x300, "A1 0F 8A FF 4A FF"));
        assertTrue(ch.keepAlive());
        assertTrue(ch.isOpen());
        script.assertComplete();
    }

    @Test
    @DisplayName("keep-alive A3 with no answer: false after 200 ms, channel dropped with A8")
    void keepAliveTimeout() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "A3").expect(0x740, "A8");
        assertFalse(ch.keepAlive());
        assertFalse(ch.isOpen());
        assertFalse(ch.keepAlive(), "a closed channel sends nothing");
        script.assertComplete();
    }

    @Test
    @DisplayName("module channel test A3 while we wait: we answer A1 with our timing")
    void moduleChannelTest() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "A3"), reply(0x300, "10 00 02 50 89"))
                .expect(0x740, "A1 0F 8A FF 32 FF")
                .expect(0x740, "B1");
        assertEquals(received("50 89"), ch.exchange(Hex.parse("10 89"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("close sends A8 and waits for the module's A8; closing twice sends nothing more")
    void close() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "A8", reply(0x300, "A8"));
        ch.close();
        ch.close();
        assertFalse(ch.isOpen());
        assertEquals(Response.Failed.Kind.CHANNEL_CLOSED,
                assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT)).kind());
        script.assertComplete();
    }

    @Test
    @DisplayName("module disconnects with A8 while we wait: DISCONNECTED_BY_MODULE, we answer A8")
    void moduleDisconnects() throws Exception {
        Tp20Channel ch = connected();
        script.expect(0x740, "10 00 02 10 89", reply(0x300, "B1"), reply(0x300, "A8"))
                .expect(0x740, "A8");
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));
        assertEquals(Response.Failed.Kind.DISCONNECTED_BY_MODULE, f.kind());
        assertFalse(ch.isOpen());
        script.assertComplete();
    }

    @Test
    @DisplayName("adapter unplugged while waiting: ADAPTER_ERROR, channel closed")
    void adapterFailure() throws Exception {
        Tp20Channel ch = connected();
        adapter.failReceive(new CanAdapterException("unplugged"));
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("10 89"), TIMEOUT));
        assertEquals(Response.Failed.Kind.ADAPTER_ERROR, f.kind());
        assertFalse(ch.isOpen());
    }

    @Test
    @DisplayName("Received copies its bytes in and out")
    void receivedIsImmutable() {
        byte[] bytes = Hex.parse("50 89");
        Response.Received r = new Response.Received(bytes);
        bytes[0] = 0;
        r.payload()[1] = 0;
        assertArrayEquals(Hex.parse("50 89"), r.payload());
        assertEquals(0x89, r.byteAt(1));
        assertEquals("Received[50 89]", r.toString());
    }
}
