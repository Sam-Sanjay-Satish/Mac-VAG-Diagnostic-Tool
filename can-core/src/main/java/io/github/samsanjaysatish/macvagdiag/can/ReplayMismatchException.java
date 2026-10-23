package io.github.samsanjaysatish.macvagdiag.can;

import java.util.Optional;

/** In an interactive replay, a sent frame did not match the next recorded tester frame (or there was none). */
public final class ReplayMismatchException extends CanAdapterException {

    private static final long serialVersionUID = 1L;

    // transient: CanFrame is not Serializable, and these exceptions are never serialized.
    private final transient CanFrame expected;
    private final transient CanFrame sent;

    ReplayMismatchException(CanFrame expected, CanFrame sent, int frameIndex) {
        super(expected == null
                ? "Replay: sent " + sent + " but the capture has no more tester frames"
                : "Replay: sent " + sent + " but capture frame #" + frameIndex + " is " + expected);
        this.expected = expected;
        this.sent = sent;
    }

    /** The recorded tester frame that was expected, or empty if the capture had none left. */
    public Optional<CanFrame> expected() {
        return Optional.ofNullable(expected);
    }

    public CanFrame sent() {
        return sent;
    }
}
