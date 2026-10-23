package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.CanAdapterException;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An ISO-TP (ISO 15765-2) link with 11-bit normal addressing and classic 8-byte CAN frames. Unguarded:
 * package-private, reachable only through {@link GuardedChannel}.
 *
 * <p>Frames: single {@code 0L}, first {@code 1L LL} (12-bit length), consecutive {@code 2N} (N = 1..F, then 0),
 * flow control {@code 30 BS STmin} (continue) / {@code 31} (wait) / {@code 32} (overflow). Frame formats follow
 * the standard; the VW-specific values in {@link Parameters#DEFAULTS} (padding, our block size and STmin, the
 * timeouts and wait-frame cap) are UNCONFIRMED until checked against the capture.
 */
final class IsoTpChannel implements RawChannel {

    private static final Logger log = LoggerFactory.getLogger(IsoTpChannel.class);

    static final int PCI_SINGLE = 0x0;
    static final int PCI_FIRST = 0x1;
    static final int PCI_CONSECUTIVE = 0x2;
    static final int PCI_FLOW_CONTROL = 0x3;
    static final int FS_CONTINUE = 0x0;
    static final int FS_WAIT = 0x1;
    static final int FS_OVERFLOW = 0x2;

    static final int FRAME_LENGTH = 8;
    static final int SINGLE_FRAME_MAX = 7;
    static final int FIRST_FRAME_DATA_BYTES = 6;
    static final int CONSECUTIVE_FRAME_DATA_BYTES = 7;
    static final int MAX_MESSAGE_LENGTH = 0xFFF;

    /**
     * Our side's ISO-TP settings.
     *
     * @param blockSize BS we send in our flow control (0 = send everything, no further flow control)
     * @param separationTimeMin STmin byte we send in our flow control
     * @param padding byte used to pad every frame we send to 8 bytes, or -1 for no padding
     * @param flowControlTimeout N_Bs: how long we wait for the module's flow control
     * @param consecutiveFrameTimeout N_Cr: longest gap we accept between the module's consecutive frames
     * @param maxWaitFrames N_WFTmax: how many {@code 31} wait frames in a row we accept
     */
    record Parameters(int blockSize, int separationTimeMin, int padding, Duration flowControlTimeout,
            Duration consecutiveFrameTimeout, int maxWaitFrames) {

        /**
         * UNCONFIRMED: pad with {@code AA} (VW often uses {@code AA} or {@code 55}), our flow control
         * {@code 30 00 00}, N_Bs and N_Cr 1000 ms (ISO 15765-2 defaults), at most 10 wait frames.
         */
        static final Parameters DEFAULTS = new Parameters(0, 0x00, 0xAA, Duration.ofMillis(1000),
                Duration.ofMillis(1000), 10);

        Parameters {
            Objects.requireNonNull(flowControlTimeout, "flowControlTimeout");
            Objects.requireNonNull(consecutiveFrameTimeout, "consecutiveFrameTimeout");
            if (blockSize < 0 || blockSize > 0xFF || separationTimeMin < 0 || separationTimeMin > 0xFF
                    || padding < -1 || padding > 0xFF || maxWaitFrames < 0) {
                throw new IllegalArgumentException("Bad ISO-TP parameters");
            }
        }
    }

    private final BusGate bus;
    private final TimeSource time;
    private final int txId;
    private final int rxId;
    private final Parameters params;
    private boolean open = true;
    private Response.Failed failure;

    private IsoTpChannel(BusGate bus, int txId, int rxId, Parameters params) {
        this.bus = bus;
        this.time = bus.time();
        this.txId = txId;
        this.rxId = rxId;
        this.params = params;
    }

    /**
     * Opens an ISO-TP link. Nothing is sent.
     *
     * @throws TransportException if the request/response pair isn't one of the known UDS IDs
     */
    static IsoTpChannel open(BusGate bus, int requestId, int responseId, Parameters params) {
        Optional<Integer> expectedResponse = DiagnosticCanIds.isoTpResponseIdFor(requestId);
        if (expectedResponse.isEmpty() || expectedResponse.get() != responseId) {
            String msg = String.format("ISO-TP route %03X/%03X is not in the expected diagnostic set %s",
                    requestId, responseId, DiagnosticCanIds.ISO_TP_REQUEST_TO_RESPONSE.entrySet().stream()
                            .map(e -> String.format("%03X/%03X", e.getKey(), e.getValue())).sorted().toList());
            log.error("Refusing to open: {}", msg);
            throw new TransportException(TransportException.Reason.DISALLOWED_CAN_ID, msg);
        }
        bus.permit(requestId);
        return new IsoTpChannel(bus, requestId, responseId, params);
    }

    /** STmin byte → time: {@code 00..7F} ms, {@code F1..F9} 100..900 µs, reserved values → 127 ms (ISO 15765-2). */
    static Duration separationTime(int stMin) {
        if (stMin <= 0x7F) {
            return Duration.ofMillis(stMin);
        }
        if (stMin >= 0xF1 && stMin <= 0xF9) {
            return Duration.ofNanos((stMin - 0xF0) * 100_000L);
        }
        return Duration.ofMillis(0x7F);
    }

    @Override
    public synchronized Response exchange(byte[] payload, Duration timeout) throws InterruptedException {
        if (!open) {
            return closedResult();
        }
        try {
            discardStale();
            Optional<Response.Failed> sendFailure = sendMessage(payload);
            if (sendFailure.isPresent()) {
                return sendFailure.get();
            }
            return receiveMessage(time.deadlineAfter(timeout), timeout);
        } catch (CanAdapterException e) {
            return fail(Response.Failed.Kind.ADAPTER_ERROR, "Adapter failed: " + e.getMessage());
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
            return fail(Response.Failed.Kind.ADAPTER_ERROR, "Adapter failed: " + e.getMessage());
        }
    }

    /** ISO-TP has no transport keep-alive; UDS Tester Present is the application's job. */
    @Override
    public synchronized boolean keepAlive() {
        return open;
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        open = false;
    }

    // ---------------------------------------------------------------- sending

    private Optional<Response.Failed> sendMessage(byte[] payload) throws InterruptedException {
        int length = payload.length;
        if (length <= SINGLE_FRAME_MAX) {
            byte[] sf = new byte[1 + length];
            sf[0] = (byte) ((PCI_SINGLE << 4) | length);
            System.arraycopy(payload, 0, sf, 1, length);
            sendFrame(sf);
            return Optional.empty();
        }
        if (length > MAX_MESSAGE_LENGTH) {
            return Optional.of(new Response.Failed(Response.Failed.Kind.PROTOCOL_ERROR,
                    "Message of " + length + " bytes exceeds the ISO-TP maximum of " + MAX_MESSAGE_LENGTH));
        }
        byte[] ff = new byte[FRAME_LENGTH];
        ff[0] = (byte) ((PCI_FIRST << 4) | (length >> 8));
        ff[1] = (byte) length;
        System.arraycopy(payload, 0, ff, 2, FIRST_FRAME_DATA_BYTES);
        sendFrame(ff);

        int offset = FIRST_FRAME_DATA_BYTES;
        int sn = 1;
        while (offset < length) {
            Object result = awaitFlowControl();
            if (result instanceof Response.Failed failed) {
                return Optional.of(failed);
            }
            FlowControl fc = (FlowControl) result;
            Duration gap = separationTime(fc.stMin());
            long lastFrameAt = Long.MIN_VALUE;
            for (int inBlock = 0; offset < length && (fc.blockSize() == 0 || inBlock < fc.blockSize()); inBlock++) {
                if (lastFrameAt != Long.MIN_VALUE) {
                    time.sleepUntil(lastFrameAt + gap.toNanos());
                }
                int end = Math.min(offset + CONSECUTIVE_FRAME_DATA_BYTES, length);
                byte[] cf = new byte[1 + end - offset];
                cf[0] = (byte) ((PCI_CONSECUTIVE << 4) | sn);
                System.arraycopy(payload, offset, cf, 1, end - offset);
                sendFrame(cf);
                lastFrameAt = time.nanoTime();
                sn = (sn + 1) & 0x0F;
                offset = end;
            }
        }
        return Optional.empty();
    }

    private record FlowControl(int blockSize, int stMin) {
    }

    /** Returns a {@link FlowControl} to continue, or a {@link Response.Failed}. */
    private Object awaitFlowControl() throws InterruptedException {
        int waits = 0;
        long deadline = time.deadlineAfter(params.flowControlTimeout());
        while (true) {
            Optional<CanFrame> next = bus.receiveOn(rxId, deadline);
            if (next.isEmpty()) {
                return new Response.Failed(Response.Failed.Kind.TRANSPORT_TIMEOUT, String.format(
                        "No flow control on %03X within %d ms", rxId, params.flowControlTimeout().toMillis()));
            }
            CanFrame f = next.get();
            if (f.length() == 0 || f.dataByte(0) >> 4 != PCI_FLOW_CONTROL) {
                continue; // not a flow control: ignored while sending
            }
            int status = f.dataByte(0) & 0x0F;
            switch (status) {
                case FS_CONTINUE -> {
                    if (f.length() < 3) {
                        return new Response.Failed(Response.Failed.Kind.PROTOCOL_ERROR, "Short flow control: " + f);
                    }
                    return new FlowControl(f.dataByte(1), f.dataByte(2));
                }
                case FS_WAIT -> {
                    if (++waits > params.maxWaitFrames()) {
                        return new Response.Failed(Response.Failed.Kind.PROTOCOL_ERROR, String.format(
                                "Module sent more than %d wait frames", params.maxWaitFrames()));
                    }
                    deadline = time.deadlineAfter(params.flowControlTimeout());
                }
                case FS_OVERFLOW -> {
                    return new Response.Failed(Response.Failed.Kind.RECEIVER_OVERFLOW,
                            "Module reported overflow (flow control 32): " + f);
                }
                default -> {
                    return new Response.Failed(Response.Failed.Kind.PROTOCOL_ERROR, "Unknown flow status: " + f);
                }
            }
        }
    }

    private void sendFlowControl() {
        sendFrame(new byte[] {(byte) ((PCI_FLOW_CONTROL << 4) | FS_CONTINUE), (byte) params.blockSize(),
                (byte) params.separationTimeMin()});
    }

    private void sendFrame(byte[] data) {
        byte[] frame = data;
        if (params.padding() >= 0 && data.length < FRAME_LENGTH) {
            frame = Arrays.copyOf(data, FRAME_LENGTH);
            Arrays.fill(frame, data.length, FRAME_LENGTH, (byte) params.padding());
        }
        bus.send(new CanFrame(txId, false, frame));
    }

    // ---------------------------------------------------------------- receiving

    private void discardStale() throws InterruptedException {
        while (true) {
            Optional<CanFrame> stale = bus.receiveOn(rxId, time.nanoTime());
            if (stale.isEmpty()) {
                return;
            }
            log.debug("Discarding stale ISO-TP frame {}", stale.get());
        }
    }

    private Response receiveMessage(long deadline, Duration timeout) throws InterruptedException {
        ByteArrayOutputStream buffer = null;
        int expectedLength = 0;
        int nextSn = 0;
        int inBlock = 0;
        long cfDeadline = Long.MAX_VALUE;
        while (true) {
            long waitUntil = buffer == null ? deadline : Math.min(deadline, cfDeadline);
            Optional<CanFrame> next = bus.receiveOn(rxId, waitUntil);
            if (next.isEmpty()) {
                if (buffer != null && cfDeadline <= deadline) {
                    return new Response.Failed(Response.Failed.Kind.TRANSPORT_TIMEOUT, String.format(
                            "No consecutive frame on %03X within %d ms (%d of %d bytes received)", rxId,
                            params.consecutiveFrameTimeout().toMillis(), buffer.size(), expectedLength));
                }
                return new Response.TimedOut(timeout);
            }
            CanFrame f = next.get();
            if (f.length() == 0) {
                continue;
            }
            int b0 = f.dataByte(0);
            switch (b0 >> 4) {
                case PCI_SINGLE -> {
                    int length = b0 & 0x0F;
                    if (length == 0 || length > f.length() - 1) {
                        log.debug("Ignoring invalid single frame {}", f);
                        continue;
                    }
                    if (buffer != null) {
                        log.debug("Single frame interrupted a multi-frame message on {}", String.format("%03X", rxId));
                    }
                    return new Response.Received(Arrays.copyOfRange(f.data(), 1, 1 + length));
                }
                case PCI_FIRST -> {
                    int length = ((b0 & 0x0F) << 8) | f.dataByte(1);
                    if (f.length() < FRAME_LENGTH || length <= SINGLE_FRAME_MAX) {
                        log.debug("Ignoring invalid first frame {}", f);
                        continue;
                    }
                    if (buffer != null) {
                        log.debug("First frame interrupted a multi-frame message on {}", String.format("%03X", rxId));
                    }
                    buffer = new ByteArrayOutputStream(length);
                    buffer.write(f.data(), 2, FIRST_FRAME_DATA_BYTES);
                    expectedLength = length;
                    nextSn = 1;
                    inBlock = 0;
                    sendFlowControl();
                    cfDeadline = time.deadlineAfter(params.consecutiveFrameTimeout());
                }
                case PCI_CONSECUTIVE -> {
                    if (buffer == null) {
                        log.debug("Ignoring unexpected consecutive frame {}", f);
                        continue;
                    }
                    int sn = b0 & 0x0F;
                    if (sn != nextSn) {
                        return new Response.Failed(Response.Failed.Kind.PROTOCOL_ERROR, String.format(
                                "Consecutive frame sequence %X, expected %X: %s", sn, nextSn, f));
                    }
                    int take = Math.min(expectedLength - buffer.size(), f.length() - 1);
                    buffer.write(f.data(), 1, take);
                    if (buffer.size() == expectedLength) {
                        return new Response.Received(buffer.toByteArray());
                    }
                    nextSn = (nextSn + 1) & 0x0F;
                    if (params.blockSize() > 0 && ++inBlock == params.blockSize()) {
                        inBlock = 0;
                        sendFlowControl();
                    }
                    cfDeadline = time.deadlineAfter(params.consecutiveFrameTimeout());
                }
                default -> log.debug("Ignoring ISO-TP frame {}", f);
            }
        }
    }

    private Response.Failed fail(Response.Failed.Kind kind, String detail) {
        if (failure == null) {
            failure = new Response.Failed(kind, detail);
            log.warn("ISO-TP channel {}/{} failed: {}", String.format("%03X", txId), String.format("%03X", rxId), detail);
        }
        open = false;
        return failure;
    }

    private Response.Failed closedResult() {
        return failure != null ? failure
                : new Response.Failed(Response.Failed.Kind.CHANNEL_CLOSED,
                        String.format("ISO-TP channel %03X/%03X is closed", txId, rxId));
    }

    @Override
    public String toString() {
        return String.format("IsoTpChannel[tx %03X, rx %03X]", txId, rxId);
    }
}
