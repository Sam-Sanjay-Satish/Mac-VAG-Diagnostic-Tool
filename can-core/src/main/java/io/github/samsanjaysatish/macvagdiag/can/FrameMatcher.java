package io.github.samsanjaysatish.macvagdiag.can;

/**
 * Decides whether a frame we send matches the tester frame recorded at the same point of a capture,
 * for {@link ReplayAdapter} in interactive mode.
 *
 * <p>Only {@link #exact()} exists so far. A tolerant matcher (ignoring fields that legitimately differ
 * between VCDS and us, such as TP 2.0 channel IDs and sequence numbers) is designed after the sniffing
 * session, once the real differences are known.
 */
@FunctionalInterface
public interface FrameMatcher {

    boolean matches(CanFrame recorded, CanFrame sent);

    /** Same ID, ID type and data bytes. */
    static FrameMatcher exact() {
        return CanFrame::equals;
    }
}
