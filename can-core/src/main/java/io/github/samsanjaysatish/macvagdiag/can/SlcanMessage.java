package io.github.samsanjaysatish.macvagdiag.can;

/** One decoded line received from an slcan adapter. */
public sealed interface SlcanMessage {

    /** A data frame received from the bus ({@code t...} or {@code T...}). */
    record Received(CanFrame frame) implements SlcanMessage {
    }

    /**
     * A remote (RTR) frame received from the bus ({@code r...} or {@code R...}). {@link CanFrame} does
     * not model remote frames, so adapters count and skip these.
     */
    record RemoteFrame(int id, boolean extended, int length) implements SlcanMessage {
    }

    /** A command was accepted: an empty line, or Lawicel's {@code z} / {@code Z} after a transmit. */
    record Ack() implements SlcanMessage {
    }

    /** A command was rejected: {@code \a} (BEL, 0x07). */
    record Error() implements SlcanMessage {
    }
}
