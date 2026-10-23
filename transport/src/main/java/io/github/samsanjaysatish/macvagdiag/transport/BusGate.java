package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import io.github.samsanjaysatish.macvagdiag.can.TimestampedFrame;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The raw channels' only access to the adapter. Every frame is checked before {@link CanAdapter#send}: it must be
 * an 11-bit ID that this channel was given ({@link #permit}) and that is in the fixed {@link DiagnosticCanIds} set.
 * Anything else is refused, logged at ERROR, and thrown as {@link TransportException.Reason#DISALLOWED_CAN_ID}.
 *
 * <p>Receiving goes through here too, with deadlines on the factory's {@link TimeSource}.
 */
final class BusGate {

    private static final Logger log = LoggerFactory.getLogger(BusGate.class);

    private final CanAdapter adapter;
    private final TimeSource time;
    private int permittedId = -1; // -1: nothing permitted

    BusGate(CanAdapter adapter, TimeSource time) {
        this.adapter = adapter;
        this.time = time;
    }

    TimeSource time() {
        return time;
    }

    /**
     * Lets this channel transmit on {@code id} (and only on it, replacing any earlier permit).
     *
     * @throws TransportException if {@code id} is outside the fixed diagnostic set
     */
    void permit(int id) {
        if (!DiagnosticCanIds.isPermittedTransmitId(id)) {
            String msg = String.format("CAN ID %03X is outside the expected diagnostic set", id);
            log.error("Refusing to transmit: {}", msg);
            throw new TransportException(TransportException.Reason.DISALLOWED_CAN_ID, msg);
        }
        permittedId = id;
    }

    /** Sends one frame after the CAN ID checks. Adapter failures propagate as {@code CanAdapterException}. */
    void send(CanFrame frame) {
        if (frame.isExtended() || frame.id() != permittedId || !DiagnosticCanIds.isPermittedTransmitId(frame.id())) {
            String msg = "Refusing to send " + frame + ": CAN ID not permitted for this channel";
            log.error(msg);
            throw new TransportException(TransportException.Reason.DISALLOWED_CAN_ID, msg);
        }
        adapter.send(frame);
    }

    /** The next frame on any ID before {@code deadlineNanos}, or empty. A deadline already passed polls once. */
    Optional<TimestampedFrame> receiveUntil(long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - time.nanoTime();
        return adapter.receive(Duration.ofNanos(Math.max(0, remaining)));
    }

    /** The next frame on {@code id} before {@code deadlineNanos}; frames on other IDs are skipped. */
    Optional<CanFrame> receiveOn(int id, long deadlineNanos) throws InterruptedException {
        while (true) {
            Optional<TimestampedFrame> next = receiveUntil(deadlineNanos);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            CanFrame frame = next.get().frame();
            if (!frame.isExtended() && frame.id() == id) {
                return Optional.of(frame);
            }
            // Another node's traffic: skip it. Past the deadline the next poll waits zero and only drains.
        }
    }
}
