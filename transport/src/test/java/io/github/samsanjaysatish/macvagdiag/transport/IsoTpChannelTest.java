package io.github.samsanjaysatish.macvagdiag.transport;

import static io.github.samsanjaysatish.macvagdiag.transport.ScriptedBus.reply;
import static io.github.samsanjaysatish.macvagdiag.transport.ScriptedBus.replyAfter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ISO-TP against hand-written frames on {@code 7E0 → 7E8}. Padding {@code AA} and the IDs are UNCONFIRMED for this
 * car; frame formats are ISO 15765-2.
 */
class IsoTpChannelTest {

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

    private IsoTpChannel open(IsoTpChannel.Parameters params) {
        return IsoTpChannel.open(new BusGate(adapter, time), 0x7E0, 0x7E8, params);
    }

    private IsoTpChannel open() {
        return open(IsoTpChannel.Parameters.DEFAULTS);
    }

    private static Response.Received received(String hex) {
        return new Response.Received(Hex.parse(hex));
    }

    private static byte[] counting(int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    private static String hexRange(byte[] b, int from, int to) {
        return Hex.format(java.util.Arrays.copyOfRange(b, from, to));
    }

    /** {@code hex} padded with AA to 8 bytes. */
    private static String padded(String hex) {
        StringBuilder sb = new StringBuilder(hex);
        for (int n = Hex.parse(hex).length; n < 8; n++) {
            sb.append(" AA");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("opening sends nothing")
    void openSendsNothing() {
        IsoTpChannel ch = open();
        assertTrue(ch.isOpen());
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    @DisplayName("single frame: 7E0 03 22 F1 87 AA AA AA AA → 7E8 07 62 F1 87 30 33 4C 39")
    void singleFrame() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 87 AA AA AA AA", reply(0x7E8, "07 62 F1 87 30 33 4C 39"));
        assertEquals(received("62 F1 87 30 33 4C 39"), ch.exchange(Hex.parse("22 F1 87"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("FF 10 14 + our flow control 30 00 00 + CF 21, 22: 20-byte response reassembled")
    void firstFrameAndConsecutiveFrames() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "10 14 62 F1 90 54 45 53"))
                .expect(0x7E0, "30 00 00 AA AA AA AA AA", reply(0x7E8, "21 54 56 49 4E 30 31 32"),
                        reply(0x7E8, "22 33 34 35 36 37 38 39"));

        assertEquals(received("62 F1 90 54 45 53 54 56 49 4E 30 31 32 33 34 35 36 37 38 39"),
                ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("our block size 2: flow control 30 02 00 again after every 2 consecutive frames")
    void ourBlockSize() throws Exception {
        IsoTpChannel ch = open(new IsoTpChannel.Parameters(2, 0x00, 0xAA, Duration.ofMillis(1000),
                Duration.ofMillis(1000), 10));
        byte[] r = counting(30);
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "10 1E " + hexRange(r, 0, 6)))
                .expect(0x7E0, "30 02 00 AA AA AA AA AA", reply(0x7E8, "21 " + hexRange(r, 6, 13)),
                        reply(0x7E8, "22 " + hexRange(r, 13, 20)))
                .expect(0x7E0, "30 02 00 AA AA AA AA AA", reply(0x7E8, "23 " + hexRange(r, 20, 27)),
                        reply(0x7E8, padded("24 " + hexRange(r, 27, 30))));

        assertEquals(new Response.Received(r), ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("multi-frame send: module flow control 30 02 14 (BS 2, STmin 20 ms), then 30 00 00")
    void multiFrameSendWithBlockSizeAndStMin() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(30);
        script.expect(0x7E0, "10 1E " + hexRange(p, 0, 6), reply(0x7E8, "30 02 14 AA AA AA AA AA"))
                .expect(0x7E0, "21 " + hexRange(p, 6, 13))
                .expect(0x7E0, "22 " + hexRange(p, 13, 20), reply(0x7E8, "30 00 00 AA AA AA AA AA"))
                .expect(0x7E0, "23 " + hexRange(p, 20, 27))
                .expect(0x7E0, padded("24 " + hexRange(p, 27, 30)), reply(0x7E8, "02 7E 00 AA AA AA AA AA"));

        assertEquals(received("7E 00"), ch.exchange(p, TIMEOUT));
        script.assertComplete();
        List<Long> t = script.sendTimes(); // FF, CF21, CF22, CF23, CF24
        assertEquals(0, t.get(1) - t.get(0), "first CF goes straight after the flow control");
        assertEquals(Duration.ofMillis(20).toNanos(), t.get(2) - t.get(1), "STmin 14 = 20 ms");
        assertEquals(0, t.get(4) - t.get(3), "second flow control says STmin 0");
    }

    @Test
    @DisplayName("STmin F5 = 500 µs between consecutive frames")
    void stMinMicroseconds() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(20);
        script.expect(0x7E0, "10 14 " + hexRange(p, 0, 6), reply(0x7E8, "30 00 F5 AA AA AA AA AA"))
                .expect(0x7E0, "21 " + hexRange(p, 6, 13))
                .expect(0x7E0, "22 " + hexRange(p, 13, 20), reply(0x7E8, "02 7E 00 AA AA AA AA AA"));
        assertEquals(received("7E 00"), ch.exchange(p, TIMEOUT));
        List<Long> t = script.sendTimes();
        assertEquals(500_000, t.get(2) - t.get(1));
    }

    @Test
    @DisplayName("STmin decoding: 00..7F ms, F1..F9 100..900 µs, reserved 80 / F0 / FA → 127 ms")
    void separationTimeDecoding() {
        assertEquals(Duration.ZERO, IsoTpChannel.separationTime(0x00));
        assertEquals(Duration.ofMillis(20), IsoTpChannel.separationTime(0x14));
        assertEquals(Duration.ofMillis(127), IsoTpChannel.separationTime(0x7F));
        assertEquals(Duration.ofNanos(100_000), IsoTpChannel.separationTime(0xF1));
        assertEquals(Duration.ofNanos(900_000), IsoTpChannel.separationTime(0xF9));
        assertEquals(Duration.ofMillis(127), IsoTpChannel.separationTime(0x80));
        assertEquals(Duration.ofMillis(127), IsoTpChannel.separationTime(0xF0));
        assertEquals(Duration.ofMillis(127), IsoTpChannel.separationTime(0xFA));
    }

    @Test
    @DisplayName("wait frame 31 00 00, then 30 00 00 500 ms later: the send continues")
    void waitFrame() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(10);
        script.expect(0x7E0, "10 0A " + hexRange(p, 0, 6), reply(0x7E8, "31 00 00 AA AA AA AA AA"),
                        replyAfter(Duration.ofMillis(500), 0x7E8, "30 00 00 AA AA AA AA AA"))
                .expect(0x7E0, padded("21 " + hexRange(p, 6, 10)), reply(0x7E8, "02 7E 00 AA AA AA AA AA"));
        assertEquals(received("7E 00"), ch.exchange(p, TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("more than 10 wait frames: PROTOCOL_ERROR, channel stays open")
    void tooManyWaitFrames() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(10);
        SimulatedAdapter.Reply[] waits = new SimulatedAdapter.Reply[11];
        for (int i = 0; i < waits.length; i++) {
            waits[i] = reply(0x7E8, "31 00 00 AA AA AA AA AA");
        }
        script.expect(0x7E0, "10 0A " + hexRange(p, 0, 6), waits);
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(p, TIMEOUT));
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR, f.kind());
        assertTrue(ch.isOpen());
        script.assertComplete();
    }

    @Test
    @DisplayName("overflow 32 00 00: RECEIVER_OVERFLOW, no consecutive frames sent")
    void overflow() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(10);
        script.expect(0x7E0, "10 0A " + hexRange(p, 0, 6), reply(0x7E8, "32 00 00 AA AA AA AA AA"));
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(p, TIMEOUT));
        assertEquals(Response.Failed.Kind.RECEIVER_OVERFLOW, f.kind());
        script.assertComplete();
    }

    @Test
    @DisplayName("unknown flow status 35: PROTOCOL_ERROR")
    void unknownFlowStatus() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(10);
        script.expect(0x7E0, "10 0A " + hexRange(p, 0, 6), reply(0x7E8, "35 00 00 AA AA AA AA AA"));
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR,
                assertInstanceOf(Response.Failed.class, ch.exchange(p, TIMEOUT)).kind());
    }

    @Test
    @DisplayName("no flow control after FF: TRANSPORT_TIMEOUT after N_Bs 1000 ms")
    void flowControlTimeout() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(10);
        script.expect(0x7E0, "10 0A " + hexRange(p, 0, 6));
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(p, Duration.ofSeconds(5)));
        assertEquals(Response.Failed.Kind.TRANSPORT_TIMEOUT, f.kind());
        assertEquals(Duration.ofMillis(1000), time.elapsed());
    }

    @Test
    @DisplayName("CF sequence 21 then 23: PROTOCOL_ERROR, channel stays usable")
    void consecutiveFrameSequenceError() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "10 14 62 F1 90 54 45 53"))
                .expect(0x7E0, "30 00 00 AA AA AA AA AA", reply(0x7E8, "21 54 56 49 4E 30 31 32"),
                        reply(0x7E8, "23 33 34 35 36 37 38 39"))
                .expect(0x7E0, "03 22 F1 87 AA AA AA AA", reply(0x7E8, "03 62 F1 87 AA AA AA AA"));

        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR, f.kind());
        assertTrue(ch.isOpen());
        assertEquals(received("62 F1 87"), ch.exchange(Hex.parse("22 F1 87"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("gap after FF longer than N_Cr 1000 ms: TRANSPORT_TIMEOUT")
    void consecutiveFrameTimeout() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "10 14 62 F1 90 54 45 53"))
                .expect(0x7E0, "30 00 00 AA AA AA AA AA");
        Response.Failed f = assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("22 F1 90"), Duration.ofSeconds(5)));
        assertEquals(Response.Failed.Kind.TRANSPORT_TIMEOUT, f.kind());
        assertEquals(Duration.ofMillis(1000), time.elapsed());
    }

    @Test
    @DisplayName("no response: TimedOut after exactly the caller's 1 s")
    void responseTimeout() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA");
        assertEquals(new Response.TimedOut(TIMEOUT), ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
        assertEquals(TIMEOUT, time.elapsed());
        assertTrue(ch.isOpen());
    }

    @Test
    @DisplayName("125-byte response: consecutive frame sequence wraps 2F → 20 → 21")
    void receiveSequenceWraparound() throws Exception {
        IsoTpChannel ch = open();
        byte[] r = counting(125);
        List<SimulatedAdapter.Reply> cfs = new ArrayList<>();
        int sn = 1;
        for (int offset = 6; offset < 125; offset += 7) {
            cfs.add(reply(0x7E8, padded(String.format("2%X ", sn) + hexRange(r, offset, Math.min(offset + 7, 125)))));
            sn = (sn + 1) & 0x0F;
        }
        assertEquals(17, cfs.size());
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "10 7D " + hexRange(r, 0, 6)))
                .expect(0x7E0, "30 00 00 AA AA AA AA AA", cfs.toArray(SimulatedAdapter.Reply[]::new));
        assertEquals(new Response.Received(r), ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("125-byte request: consecutive frame sequence wraps 2F → 20 → 21")
    void sendSequenceWraparound() throws Exception {
        IsoTpChannel ch = open();
        byte[] p = counting(125);
        script.expect(0x7E0, "10 7D " + hexRange(p, 0, 6), reply(0x7E8, "30 00 00 AA AA AA AA AA"));
        int sn = 1;
        for (int offset = 6; offset < 125; offset += 7) {
            String frame = padded(String.format("2%X ", sn) + hexRange(p, offset, Math.min(offset + 7, 125)));
            if (offset + 7 >= 125) {
                script.expect(0x7E0, frame, reply(0x7E8, "02 7E 00 AA AA AA AA AA"));
            } else {
                script.expect(0x7E0, frame);
            }
            sn = (sn + 1) & 0x0F;
        }
        assertEquals(received("7E 00"), ch.exchange(p, TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("7F 22 78 (response pending), then awaitResponse gets 62 F1 87 ... 3 s later without resending")
    void responsePending() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 87 AA AA AA AA", reply(0x7E8, "03 7F 22 78 AA AA AA AA"),
                replyAfter(Duration.ofSeconds(3), 0x7E8, "05 62 F1 87 30 33 AA AA"));
        assertEquals(received("7F 22 78"), ch.exchange(Hex.parse("22 F1 87"), TIMEOUT));
        assertEquals(received("62 F1 87 30 33"), ch.awaitResponse(Duration.ofSeconds(5)));
        assertEquals(Duration.ofSeconds(3), time.elapsed());
        script.assertComplete();
    }

    @Test
    @DisplayName("other IDs, an invalid single frame 00 and a stray flow control are ignored")
    void ignoresNoise() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 87 AA AA AA AA", reply(0x7E9, "03 62 F1 87 AA AA AA AA"),
                reply(0x7E8, "00 62 F1 87 AA AA AA AA"), reply(0x7E8, "30 00 00 AA AA AA AA AA"),
                reply(0x7E8, "21 00 00 00 00 00 00 00"), reply(0x7E8, "03 62 F1 87 AA AA AA AA"));
        assertEquals(received("62 F1 87"), ch.exchange(Hex.parse("22 F1 87"), TIMEOUT));
    }

    @Test
    @DisplayName("a single frame during a multi-frame response replaces it (ISO 15765-2)")
    void singleFrameInterruptsMultiFrame() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "10 14 62 F1 90 54 45 53"))
                .expect(0x7E0, "30 00 00 AA AA AA AA AA", reply(0x7E8, "03 7F 22 31 AA AA AA AA"));
        assertEquals(received("7F 22 31"), ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
    }

    @Test
    @DisplayName("frames waiting before a request (a late response) are discarded")
    void staleFramesDiscarded() throws Exception {
        IsoTpChannel ch = open();
        script.expect(0x7E0, "03 22 F1 87 AA AA AA AA",
                        replyAfter(Duration.ofSeconds(2), 0x7E8, "03 62 F1 87 AA AA AA AA"))
                .expect(0x7E0, "03 22 F1 90 AA AA AA AA", reply(0x7E8, "03 62 F1 90 AA AA AA AA"));
        assertInstanceOf(Response.TimedOut.class, ch.exchange(Hex.parse("22 F1 87"), TIMEOUT));
        time.advance(Duration.ofSeconds(5));
        assertEquals(received("62 F1 90"), ch.exchange(Hex.parse("22 F1 90"), TIMEOUT));
    }

    @Test
    @DisplayName("4096-byte message exceeds ISO-TP's 12-bit length: PROTOCOL_ERROR, nothing sent")
    void tooLong() throws Exception {
        IsoTpChannel ch = open();
        assertEquals(Response.Failed.Kind.PROTOCOL_ERROR,
                assertInstanceOf(Response.Failed.class, ch.exchange(new byte[4096], TIMEOUT)).kind());
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    @DisplayName("padding -1: frames go out at their real length")
    void noPadding() throws Exception {
        IsoTpChannel ch = open(new IsoTpChannel.Parameters(0, 0, -1, Duration.ofMillis(1000), Duration.ofMillis(1000), 10));
        script.expect(0x7E0, "03 22 F1 87", reply(0x7E8, "03 62 F1 87"));
        assertEquals(received("62 F1 87"), ch.exchange(Hex.parse("22 F1 87"), TIMEOUT));
        script.assertComplete();
    }

    @Test
    @DisplayName("keepAlive sends nothing on ISO-TP; close ends the channel")
    void keepAliveAndClose() throws Exception {
        IsoTpChannel ch = open();
        assertTrue(ch.keepAlive());
        ch.close();
        assertFalse(ch.isOpen());
        assertFalse(ch.keepAlive());
        assertEquals(Response.Failed.Kind.CHANNEL_CLOSED,
                assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("22 F1 87"), TIMEOUT)).kind());
        assertEquals(List.of(), adapter.sentFrames());
    }

    @Test
    @DisplayName("adapter unplugged: ADAPTER_ERROR, channel closed")
    void adapterFailure() throws Exception {
        IsoTpChannel ch = open();
        adapter.failReceive(new CanAdapterException("unplugged"));
        assertEquals(Response.Failed.Kind.ADAPTER_ERROR,
                assertInstanceOf(Response.Failed.class, ch.exchange(Hex.parse("22 F1 87"), TIMEOUT)).kind());
        assertFalse(ch.isOpen());
    }
}
