package io.github.samsanjaysatish.macvagdiag.can;

/** Thrown when something tries to send on an adapter opened in {@link AdapterMode#LISTEN_ONLY}. */
public final class ListenOnlyViolationException extends CanAdapterException {

    private static final long serialVersionUID = 1L;

    public ListenOnlyViolationException(CanFrame frame) {
        super("Refusing to send " + frame + ": adapter is open in LISTEN_ONLY mode");
    }
}
