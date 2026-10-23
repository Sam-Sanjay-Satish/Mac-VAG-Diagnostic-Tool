package io.github.samsanjaysatish.macvagdiag.transport;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * What came back on a {@link DiagnosticChannel}: a whole response message, a timeout, or a transport failure.
 *
 * <p>The transport does not interpret the message. A KWP2000/UDS negative response ({@code 7F [SID] [NRC]})
 * is a {@link Received} like any other; NRC handling (e.g. {@code 0x78} response pending) belongs to {@code diag}.
 */
public sealed interface Response {

    /**
     * A complete response message, exactly as reassembled from the transport (positive or negative).
     * The bytes are copied in and out.
     */
    record Received(byte[] payload) implements Response {

        public Received {
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        /** A copy of the message bytes. */
        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public int length() {
            return payload.length;
        }

        /** Byte {@code index} as an unsigned value 0..255, without copying. */
        public int byteAt(int index) {
            return Byte.toUnsignedInt(payload[index]);
        }

        /** The message as bus-style hex, e.g. {@code 7F 19 78}. */
        public String hex() {
            return Hex.format(payload);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Received other && Arrays.equals(payload, other.payload);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Received[" + hex() + "]";
        }
    }

    /** No (complete) response arrived within the caller's timeout. The channel is still usable. */
    record TimedOut(Duration waited) implements Response {

        public TimedOut {
            Objects.requireNonNull(waited, "waited");
        }
    }

    /**
     * The transport failed. {@link Kind} says how; {@code detail} is a readable explanation for logs and
     * reports. Unless the kind says otherwise the channel may no longer be usable: check
     * {@link DiagnosticChannel#isOpen()}.
     */
    record Failed(Kind kind, String detail) implements Response {

        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }

        public enum Kind {
            /** The channel was already closed (by us, or after an earlier failure). */
            CHANNEL_CLOSED,
            /** The module ended the channel (TP 2.0 {@code A8}). The channel is closed. */
            DISCONNECTED_BY_MODULE,
            /**
             * A transport-level wait ran out: no TP 2.0 ACK after the capped retries (channel closed), no
             * ISO-TP flow control, or a gap between ISO-TP consecutive frames. Not the caller's timeout,
             * which is {@link TimedOut}.
             */
            TRANSPORT_TIMEOUT,
            /** The module's receive buffer can't take the message (ISO-TP flow control {@code 32}). */
            RECEIVER_OVERFLOW,
            /**
             * The module broke the transport protocol: wrong sequence number, length mismatch, unknown flow
             * status, too many wait frames. A TP 2.0 channel is closed afterwards; ISO-TP stays usable.
             */
            PROTOCOL_ERROR,
            /** The CAN adapter failed (unplugged, I/O error). The channel is closed. */
            ADAPTER_ERROR
        }
    }
}
