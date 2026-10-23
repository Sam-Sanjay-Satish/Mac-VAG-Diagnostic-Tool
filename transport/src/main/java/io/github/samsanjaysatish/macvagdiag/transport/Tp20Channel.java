package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A VW TP 2.0 channel to one module. Unguarded: package-private, reachable only through {@link GuardedChannel}.
 *
 * <p><b>Every opcode, byte layout, timing value and retry count here is UNCONFIRMED</b> (from memory). They are
 * named constants so the sniffing capture can correct them in one place.
 *
 * <p>Wire format (UNCONFIRMED):
 * <ul>
 *   <li>Setup: tester on {@code 200}: {@code [addr] C0 00 10 [rx lo] [rx hi] 01}; module on {@code 200+addr}:
 *       {@code 00 D0 [tester rx lo] [rx hi] [tester tx lo] [tx hi] 01}. In the 16-bit ID fields the low byte
 *       comes first, the high byte's low 3 bits are ID bits 8-10 and {@code 0x10} flags the ID as invalid
 *       ("you choose"). {@code D6..D8} are negative replies.</li>
 *   <li>Timing: tester {@code A0 [bs] [T1] [T2] [T3] [T4]}, module {@code A1 ...}. Time bytes: bits 7-6 are the
 *       unit (0.1 ms, 1 ms, 10 ms, 100 ms), bits 5-0 the count. We use the module's block size, T1 as the ACK
 *       timeout and T3 as the minimum gap between our data packets.</li>
 *   <li>Data: {@code [op|seq]}, op {@code 0} more+ACK, {@code 1} last+ACK, {@code 2} more, {@code 3} last;
 *       {@code B[seq+1]} ACK ready, {@code 9[seq+1]} ACK not ready. The first packet of a message carries the
 *       16-bit length (big-endian) then 5 data bytes; later packets carry 7. Each side counts its own sequence
 *       numbers mod 16 from channel open, across messages.</li>
 *   <li>{@code A3} channel test → {@code A1}; {@code A8} disconnect from either side, answered with {@code A8}.</li>
 * </ul>
 * TP 2.0 frames are not padded (the DLC is the packet length).
 */
final class Tp20Channel implements RawChannel {

    private static final Logger log = LoggerFactory.getLogger(Tp20Channel.class);

    // --- Setup (UNCONFIRMED) ---
    static final int TESTER_ADDRESS = 0x00;
    static final int OP_SETUP_REQUEST = 0xC0;
    static final int OP_SETUP_POSITIVE = 0xD0;
    static final int OP_SETUP_NEGATIVE_MIN = 0xD6;
    static final int OP_SETUP_NEGATIVE_MAX = 0xD8;
    static final int APP_TYPE_KWP2000 = 0x01;
    static final int ID_INVALID_FLAG = 0x10;
    static final int ID_HIGH_BITS_MASK = 0x07;

    // --- Channel control (UNCONFIRMED) ---
    static final int OP_PARAMS_REQUEST = 0xA0;
    static final int OP_PARAMS_RESPONSE = 0xA1;
    static final int OP_CHANNEL_TEST = 0xA3;
    static final int OP_DISCONNECT = 0xA8;

    // --- Data packet opcodes, high nibble of byte 0 (UNCONFIRMED) ---
    static final int DATA_MORE_EXPECT_ACK = 0x0;
    static final int DATA_LAST_EXPECT_ACK = 0x1;
    static final int DATA_MORE_NO_ACK = 0x2;
    static final int DATA_LAST_NO_ACK = 0x3;
    static final int ACK_NOT_READY = 0x9;
    static final int ACK_READY = 0xB;

    static final int FIRST_PACKET_DATA_BYTES = 5;
    static final int PACKET_DATA_BYTES = 7;
    static final int MAX_MESSAGE_LENGTH = 0xFFFF;
    static final int MAX_BLOCK_SIZE = 15;

    /**
     * UNCONFIRMED: what we send in {@code A0}: block size 15, T1 = {@code 8A} (100 ms), T2 unused, T3 =
     * {@code 32} (5 ms), T4 unused. Check against the VCDS parameters in the capture.
     */
    static final byte[] TESTER_TIMING = {0x0F, (byte) 0x8A, (byte) 0xFF, 0x32, (byte) 0xFF};

    /** UNCONFIRMED: how long to wait for the setup reply (scan probes absent modules with this). */
    static final Duration SETUP_TIMEOUT = Duration.ofMillis(200);
    /** UNCONFIRMED: how long to wait for {@code A1} after {@code A0} / {@code A3}, and for {@code A8} after ours. */
    static final Duration CONTROL_REPLY_TIMEOUT = Duration.ofMillis(200);
    /** UNCONFIRMED: ACK timeout when the module's T1 decodes to zero. */
    static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofMillis(100);
    /** UNCONFIRMED: how often an unacknowledged block is resent before the channel is given up. */
    static final int MAX_BLOCK_RETRIES = 2;
    /** UNCONFIRMED: how many "not ready" ACKs in a row we accept before giving up. */
    static final int MAX_NOT_READY_ACKS = 10;

    private static final int NO_ACK = -1;
    private static final int READY_FLAG = 0x100;

    private final BusGate bus;
    private final TimeSource time;
    private final int moduleAddress;
    private final int txId;
    private final int rxId;
    private final int blockSize;
    private final Duration ackTimeout;
    private final Duration packetInterval;

    private boolean open = true;
    private Response.Failed failure;
    private int txSeq;
    private int rxSeq;
    // Message being received
    private ByteArrayOutputStream rxBuffer;
    private int rxExpectedLength;
    private boolean rxStale;
    private final Deque<byte[]> completed = new ArrayDeque<>();
    // Set by process(): last ACK seen (seq | READY_FLAG if ready), and whether A1 arrived.
    private int lastAck = NO_ACK;
    private boolean paramsReplySeen;

    private Tp20Channel(BusGate bus, int moduleAddress, int txId, int rxId, int blockSize, Duration ackTimeout,
            Duration packetInterval) {
        this.bus = bus;
        this.time = bus.time();
        this.moduleAddress = moduleAddress;
        this.txId = txId;
        this.rxId = rxId;
        this.blockSize = blockSize;
        this.ackTimeout = ackTimeout;
        this.packetInterval = packetInterval;
    }

    /**
     * Sets up a channel: {@code C0} setup on {@code 200}, then {@code A0}/{@code A1} timing exchange.
     *
     * @throws TransportException if the module doesn't answer, refuses, negotiates a CAN ID outside the expected
     *     set (nothing is sent on it), or replies malformed
     */
    static Tp20Channel connect(BusGate bus, int moduleAddress) throws InterruptedException {
        TimeSource time = bus.time();
        int setupReplyId = DiagnosticCanIds.TP20_SETUP_RESPONSE_BASE + moduleAddress;
        int proposedRx = DiagnosticCanIds.TP20_TESTER_RX_ID;
        try {
            bus.permit(DiagnosticCanIds.TP20_SETUP_REQUEST_ID);
            bus.send(CanFrame.standard(DiagnosticCanIds.TP20_SETUP_REQUEST_ID, moduleAddress, OP_SETUP_REQUEST,
                    0x00, ID_INVALID_FLAG, proposedRx & 0xFF, (proposedRx >> 8) & ID_HIGH_BITS_MASK,
                    APP_TYPE_KWP2000));
            CanFrame reply = awaitSetupReply(bus, setupReplyId, moduleAddress, time.deadlineAfter(SETUP_TIMEOUT));
            int testerRx = decodeId(reply.dataByte(2), reply.dataByte(3));
            int testerTx = decodeId(reply.dataByte(4), reply.dataByte(5));
            if (testerRx < 0 || testerTx < 0) {
                throw new TransportException(TransportException.Reason.PROTOCOL_ERROR,
                        "TP 2.0 setup reply with an invalid channel ID: " + reply);
            }
            if (!DiagnosticCanIds.isNegotiableTp20TxId(testerTx) || testerRx == testerTx) {
                String msg = String.format("Module %02X negotiated transmit ID %03X (receive %03X), outside the "
                        + "expected TP 2.0 range %03X..%03X; nothing sent on it", moduleAddress, testerTx, testerRx,
                        DiagnosticCanIds.TP20_NEGOTIATED_TX_MIN, DiagnosticCanIds.TP20_NEGOTIATED_TX_MAX);
                log.error(msg);
                throw new TransportException(TransportException.Reason.DISALLOWED_CAN_ID, msg);
            }
            bus.permit(testerTx); // from here on, 200 is no longer permitted for this channel
            return exchangeTiming(bus, moduleAddress, testerTx, testerRx);
        } catch (CanAdapterException e) {
            throw new TransportException(TransportException.Reason.ADAPTER_ERROR,
                    "Adapter failed during TP 2.0 setup to module " + String.format("%02X", moduleAddress), e);
        }
    }

    private static CanFrame awaitSetupReply(BusGate bus, int replyId, int moduleAddress, long deadline)
            throws InterruptedException {
        while (true) {
            Optional<CanFrame> next = bus.receiveOn(replyId, deadline);
            if (next.isEmpty()) {
                throw new TransportException(TransportException.Reason.NO_RESPONSE, String.format(
                        "No TP 2.0 setup reply from module %02X within %d ms", moduleAddress, SETUP_TIMEOUT.toMillis()));
            }
            CanFrame f = next.get();
            if (f.length() < 2 || f.dataByte(0) != TESTER_ADDRESS) {
                continue; // not addressed to us
            }
            int op = f.dataByte(1);
            if (op >= OP_SETUP_NEGATIVE_MIN && op <= OP_SETUP_NEGATIVE_MAX) {
                throw new TransportException(TransportException.Reason.REFUSED_BY_MODULE,
                        String.format("Module %02X refused the TP 2.0 channel: %s", moduleAddress, f));
            }
            if (op == OP_SETUP_POSITIVE && f.length() >= 7) {
                return f;
            }
            throw new TransportException(TransportException.Reason.PROTOCOL_ERROR,
                    "Unexpected TP 2.0 setup reply: " + f);
        }
    }

    private static Tp20Channel exchangeTiming(BusGate bus, int moduleAddress, int txId, int rxId)
            throws InterruptedException {
        byte[] request = new byte[1 + TESTER_TIMING.length];
        request[0] = (byte) OP_PARAMS_REQUEST;
        System.arraycopy(TESTER_TIMING, 0, request, 1, TESTER_TIMING.length);
        bus.send(new CanFrame(txId, false, request));
        long deadline = bus.time().deadlineAfter(CONTROL_REPLY_TIMEOUT);
        while (true) {
            Optional<CanFrame> next = bus.receiveOn(rxId, deadline);
            if (next.isEmpty()) {
                disconnectQuietly(bus, txId);
                throw new TransportException(TransportException.Reason.NO_RESPONSE,
                        String.format("Module %02X sent no A1 timing reply", moduleAddress));
            }
            CanFrame f = next.get();
            if (f.length() == 0 || f.dataByte(0) != OP_PARAMS_RESPONSE) {
                continue;
            }
            if (f.length() < 6) {
                disconnectQuietly(bus, txId);
                throw new TransportException(TransportException.Reason.PROTOCOL_ERROR, "Short A1 timing reply: " + f);
            }
            int bs = Math.clamp(f.dataByte(1), 1, MAX_BLOCK_SIZE);
            Duration t1 = decodeTime(f.dataByte(2));
            Duration t3 = decodeTime(f.dataByte(4));
            Duration ack = t1.isZero() ? DEFAULT_ACK_TIMEOUT : t1;
            log.debug("TP 2.0 channel to {}: tx {}, rx {}, block size {}, ACK timeout {}, packet gap {}",
                    String.format("%02X", moduleAddress), String.format("%03X", txId), String.format("%03X", rxId),
                    bs, ack, t3);
            return new Tp20Channel(bus, moduleAddress, txId, rxId, bs, ack, t3);
        }
    }

    /** Decodes a setup ID field: low byte, then high byte (bits 0-2 = ID bits 8-10, 0x10 = invalid). -1 if invalid. */
    static int decodeId(int low, int high) {
        if ((high & ID_INVALID_FLAG) != 0) {
            return -1;
        }
        return ((high & ID_HIGH_BITS_MASK) << 8) | low;
    }

    /** Decodes a TP 2.0 time byte: bits 7-6 unit (0.1 / 1 / 10 / 100 ms), bits 5-0 count. UNCONFIRMED. */
    static Duration decodeTime(int value) {
        int count = value & 0x3F;
        return switch ((value >> 6) & 0x03) {
            case 0 -> Duration.ofNanos(count * 100_000L);
            case 1 -> Duration.ofMillis(count);
            case 2 -> Duration.ofMillis(count * 10L);
            default -> Duration.ofMillis(count * 100L);
        };
    }

    int txId() {
        return txId;
    }

    int rxId() {
        return rxId;
    }

    int blockSize() {
        return blockSize;
    }

    Duration ackTimeout() {
        return ackTimeout;
    }

    Duration packetInterval() {
        return packetInterval;
    }

    @Override
    public synchronized Response exchange(byte[] payload, Duration timeout) throws InterruptedException {
        if (!open) {
            return closedResult();
        }
        if (payload.length > MAX_MESSAGE_LENGTH) {
            return new Response.Failed(Response.Failed.Kind.PROTOCOL_ERROR,
                    "Message of " + payload.length + " bytes exceeds the TP 2.0 maximum of " + MAX_MESSAGE_LENGTH);
        }
        try {
            discardStale();
            if (!open) {
                return closedResult();
            }
            Optional<Response.Failed> sendFailure = sendMessage(payload);
            if (sendFailure.isPresent()) {
                return sendFailure.get();
            }
            return receiveMessage(time.deadlineAfter(timeout), timeout);
        } catch (CanAdapterException e) {
            return fail(Response.Failed.Kind.ADAPTER_ERROR, "Adapter failed: " + e.getMessage(), false);
        }
    }

    @Override
    public synchronized Response awaitResponse(Duration timeout) throws InterruptedException {
        if (!open) {
            return closedResult();
        }
        try {
            return receiveMessage(time.deadlineAfter(timeout), timeout);
        } catch (CanAdapterException e) {
            return fail(Response.Failed.Kind.ADAPTER_ERROR, "Adapter failed: " + e.getMessage(), false);
        }
    }

    @Override
    public synchronized boolean keepAlive() throws InterruptedException {
        if (!open) {
            return false;
        }
        try {
            paramsReplySeen = false;
            bus.send(CanFrame.standard(txId, OP_CHANNEL_TEST));
            long deadline = time.deadlineAfter(CONTROL_REPLY_TIMEOUT);
            while (!paramsReplySeen && open) {
                Optional<CanFrame> next = bus.receiveOn(rxId, deadline);
                if (next.isEmpty()) {
                    fail(Response.Failed.Kind.TRANSPORT_TIMEOUT, "No A1 reply to channel test A3", true);
                    return false;
                }
                process(next.get());
            }
            return open;
        } catch (CanAdapterException e) {
            fail(Response.Failed.Kind.ADAPTER_ERROR, "Adapter failed: " + e.getMessage(), false);
            return false;
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        try {
            bus.send(CanFrame.standard(txId, OP_DISCONNECT));
            long deadline = time.deadlineAfter(CONTROL_REPLY_TIMEOUT);
            while (true) {
                Optional<CanFrame> next = bus.receiveOn(rxId, deadline);
                if (next.isEmpty()) {
                    log.debug("No A8 reply from module {}", String.format("%02X", moduleAddress));
                    break;
                }
                if (next.get().length() > 0 && next.get().dataByte(0) == OP_DISCONNECT) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Error while closing TP 2.0 channel to {}", String.format("%02X", moduleAddress), e);
        }
        log.debug("TP 2.0 channel to {} closed", String.format("%02X", moduleAddress));
    }

    // ---------------------------------------------------------------- sending

    /** Splits a message into packet data: the first carries the 2-byte length and up to 5 bytes, later ones 7. */
    static List<byte[]> segment(byte[] payload) {
        List<byte[]> packets = new ArrayList<>();
        int first = Math.min(FIRST_PACKET_DATA_BYTES, payload.length);
        byte[] head = new byte[2 + first];
        head[0] = (byte) (payload.length >> 8);
        head[1] = (byte) payload.length;
        System.arraycopy(payload, 0, head, 2, first);
        packets.add(head);
        for (int offset = first; offset < payload.length; offset += PACKET_DATA_BYTES) {
            packets.add(Arrays.copyOfRange(payload, offset, Math.min(offset + PACKET_DATA_BYTES, payload.length)));
        }
        return packets;
    }

    private Optional<Response.Failed> sendMessage(byte[] payload) throws InterruptedException {
        List<byte[]> packets = segment(payload);
        int n = packets.size();
        int seqBase = txSeq;
        long[] lastPacketAt = {Long.MIN_VALUE};
        for (int start = 0; start < n; ) {
            int end = Math.min(start + blockSize, n);
            int retries = 0;
            while (true) {
                for (int j = start; j < end; j++) {
                    int op = j == n - 1 ? DATA_LAST_EXPECT_ACK : (j == end - 1 ? DATA_MORE_EXPECT_ACK : DATA_MORE_NO_ACK);
                    sendPacket(op, (seqBase + j) & 0x0F, packets.get(j), lastPacketAt);
                }
                int expectedAck = (seqBase + end) & 0x0F;
                AckOutcome outcome = awaitAck(expectedAck);
                if (outcome == AckOutcome.OK) {
                    break;
                }
                if (outcome == AckOutcome.FAILED) {
                    return Optional.of(failure != null ? failure : closedResult());
                }
                if (++retries > MAX_BLOCK_RETRIES) {
                    return Optional.of(fail(Response.Failed.Kind.TRANSPORT_TIMEOUT, String.format(
                            "No ACK B%X from module %02X after %d retries", expectedAck, moduleAddress, MAX_BLOCK_RETRIES),
                            true));
                }
                log.warn("No ACK from module {} within {}; resending block (retry {}/{})",
                        String.format("%02X", moduleAddress), ackTimeout, retries, MAX_BLOCK_RETRIES);
            }
            start = end;
        }
        txSeq = (seqBase + n) & 0x0F;
        return Optional.empty();
    }

    private void sendPacket(int op, int seq, byte[] data, long[] lastPacketAt) throws InterruptedException {
        if (lastPacketAt[0] != Long.MIN_VALUE) {
            time.sleepUntil(lastPacketAt[0] + packetInterval.toNanos());
        }
        byte[] frame = new byte[1 + data.length];
        frame[0] = (byte) ((op << 4) | seq);
        System.arraycopy(data, 0, frame, 1, data.length);
        bus.send(new CanFrame(txId, false, frame));
        lastPacketAt[0] = time.nanoTime();
    }

    private enum AckOutcome { OK, TIMED_OUT, FAILED }

    private AckOutcome awaitAck(int expectedSeq) throws InterruptedException {
        lastAck = NO_ACK;
        int notReady = 0;
        long deadline = time.deadlineAfter(ackTimeout);
        while (true) {
            if (failure != null || !open) {
                return AckOutcome.FAILED;
            }
            if (lastAck != NO_ACK) {
                int seq = lastAck & 0x0F;
                boolean ready = (lastAck & READY_FLAG) != 0;
                lastAck = NO_ACK;
                if (seq != expectedSeq) {
                    fail(Response.Failed.Kind.PROTOCOL_ERROR, String.format(
                            "Module %02X acknowledged %X%X, expected B%X", moduleAddress, ready ? ACK_READY : ACK_NOT_READY,
                            seq, expectedSeq), true);
                    return AckOutcome.FAILED;
                }
                if (ready) {
                    return AckOutcome.OK;
                }
                if (++notReady > MAX_NOT_READY_ACKS) {
                    fail(Response.Failed.Kind.TRANSPORT_TIMEOUT, String.format(
                            "Module %02X stayed not ready for %d ACKs", moduleAddress, MAX_NOT_READY_ACKS), true);
                    return AckOutcome.FAILED;
                }
                deadline = time.deadlineAfter(ackTimeout); // not ready: wait for the ready ACK
                continue;
            }
            Optional<CanFrame> next = bus.receiveOn(rxId, deadline);
            if (next.isEmpty()) {
                return AckOutcome.TIMED_OUT;
            }
            process(next.get());
        }
    }

    // ---------------------------------------------------------------- receiving

    private Response receiveMessage(long deadline, Duration timeout) throws InterruptedException {
        while (true) {
            if (!completed.isEmpty()) {
                return new Response.Received(completed.poll());
            }
            if (failure != null) {
                return failure;
            }
            if (!open) {
                return closedResult();
            }
            Optional<CanFrame> next = bus.receiveOn(rxId, deadline);
            if (next.isEmpty()) {
                return new Response.TimedOut(timeout);
            }
            process(next.get());
        }
    }

    /** Handles frames already waiting (ACKing them as needed) and drops any responses nobody asked for. */
    private void discardStale() throws InterruptedException {
        while (open) {
            Optional<CanFrame> next = bus.receiveOn(rxId, time.nanoTime());
            if (next.isEmpty()) {
                break;
            }
            process(next.get());
        }
        while (!completed.isEmpty()) {
            log.debug("Discarding stale response from module {}: {}", String.format("%02X", moduleAddress),
                    Hex.format(completed.poll()));
        }
        if (rxBuffer != null) {
            rxStale = true; // a response still arriving belongs to an earlier request
        }
    }

    /** One incoming frame on our receive ID: control, ACK or data. */
    private void process(CanFrame f) throws InterruptedException {
        if (f.length() == 0) {
            return;
        }
        int b0 = f.dataByte(0);
        switch (b0) {
            case OP_DISCONNECT -> {
                fail(Response.Failed.Kind.DISCONNECTED_BY_MODULE,
                        String.format("Module %02X closed the channel (A8)", moduleAddress), true);
                return;
            }
            case OP_CHANNEL_TEST, OP_PARAMS_REQUEST -> {
                sendTimingReply();
                return;
            }
            case OP_PARAMS_RESPONSE -> {
                paramsReplySeen = true;
                return;
            }
            default -> {
            }
        }
        int op = b0 >> 4;
        int seq = b0 & 0x0F;
        switch (op) {
            case ACK_READY -> lastAck = READY_FLAG | seq;
            case ACK_NOT_READY -> lastAck = seq;
            case DATA_MORE_EXPECT_ACK, DATA_LAST_EXPECT_ACK, DATA_MORE_NO_ACK, DATA_LAST_NO_ACK -> receiveData(f, op, seq);
            default -> log.debug("Ignoring TP 2.0 frame {}", f);
        }
    }

    private void receiveData(CanFrame f, int op, int seq) {
        if (seq != rxSeq) {
            fail(Response.Failed.Kind.PROTOCOL_ERROR, String.format(
                    "Module %02X sent packet sequence %X, expected %X: %s", moduleAddress, seq, rxSeq, f), true);
            return;
        }
        rxSeq = (rxSeq + 1) & 0x0F;
        byte[] data = f.data();
        int from = 1;
        if (rxBuffer == null) {
            if (data.length < 3) {
                fail(Response.Failed.Kind.PROTOCOL_ERROR, "First TP 2.0 packet without a length: " + f, true);
                return;
            }
            rxExpectedLength = (Byte.toUnsignedInt(data[1]) << 8) | Byte.toUnsignedInt(data[2]);
            rxBuffer = new ByteArrayOutputStream(rxExpectedLength);
            from = 3;
        }
        rxBuffer.write(data, from, data.length - from);
        if (op == DATA_MORE_EXPECT_ACK || op == DATA_LAST_EXPECT_ACK) {
            bus.send(CanFrame.standard(txId, (ACK_READY << 4) | rxSeq));
        }
        boolean last = op == DATA_LAST_EXPECT_ACK || op == DATA_LAST_NO_ACK;
        int received = rxBuffer.size();
        if (received > rxExpectedLength || (last && received != rxExpectedLength)) {
            fail(Response.Failed.Kind.PROTOCOL_ERROR, String.format(
                    "Module %02X message length %d but %d bytes received", moduleAddress, rxExpectedLength, received), true);
            return;
        }
        if (last) {
            byte[] message = rxBuffer.toByteArray();
            rxBuffer = null;
            if (rxStale) {
                rxStale = false;
                log.debug("Discarding stale response from module {}: {}", String.format("%02X", moduleAddress),
                        Hex.format(message));
            } else {
                completed.add(message);
            }
        }
    }

    private void sendTimingReply() {
        byte[] reply = new byte[1 + TESTER_TIMING.length];
        reply[0] = (byte) OP_PARAMS_RESPONSE;
        System.arraycopy(TESTER_TIMING, 0, reply, 1, TESTER_TIMING.length);
        bus.send(new CanFrame(txId, false, reply));
    }

    // ---------------------------------------------------------------- failure

    /** Records the first failure, closes the channel (optionally sending A8) and returns the failure. */
    private Response.Failed fail(Response.Failed.Kind kind, String detail, boolean sendDisconnect) {
        if (failure == null) {
            failure = new Response.Failed(kind, detail);
            log.warn("TP 2.0 channel to {} failed: {}", String.format("%02X", moduleAddress), detail);
        }
        if (open) {
            open = false;
            if (sendDisconnect) {
                disconnectQuietly(bus, txId);
            }
        }
        rxBuffer = null;
        return failure;
    }

    private static void disconnectQuietly(BusGate bus, int txId) {
        try {
            bus.send(CanFrame.standard(txId, OP_DISCONNECT));
        } catch (RuntimeException e) {
            log.debug("Could not send A8", e);
        }
    }

    private Response.Failed closedResult() {
        return failure != null ? failure
                : new Response.Failed(Response.Failed.Kind.CHANNEL_CLOSED,
                        String.format("TP 2.0 channel to %02X is closed", moduleAddress));
    }

    @Override
    public String toString() {
        return String.format("Tp20Channel[module %02X, tx %03X, rx %03X]", moduleAddress, txId, rxId);
    }
}
