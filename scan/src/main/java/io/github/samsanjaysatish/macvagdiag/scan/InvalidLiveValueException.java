package io.github.samsanjaysatish.macvagdiag.scan;

/**
 * Live-data arguments were refused before anything was sent: an ID the module's catalog doesn't offer (or
 * withholds), too many values or groups, a bad duration, or no catalog yet. The message says what to do, so it
 * can be passed to an AI agent as it is.
 */
public final class InvalidLiveValueException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    InvalidLiveValueException(String message) {
        super(message);
    }
}
