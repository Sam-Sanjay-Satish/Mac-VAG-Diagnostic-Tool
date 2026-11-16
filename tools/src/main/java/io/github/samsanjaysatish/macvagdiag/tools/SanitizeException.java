package io.github.samsanjaysatish.macvagdiag.tools;

/**
 * The sanitizer refuses to produce output, e.g. because a trace of the VIN would remain. The message is shown
 * to the user as is and never contains the VIN or another masked value.
 */
final class SanitizeException extends Exception {

    private static final long serialVersionUID = 1L;

    SanitizeException(String message) {
        super(message);
    }
}
