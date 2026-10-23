package io.github.samsanjaysatish.macvagdiag.can;

/** An adapter failed: not open, closed while waiting, I/O error, unplugged, and so on. */
public class CanAdapterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CanAdapterException(String message) {
        super(message);
    }

    public CanAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
