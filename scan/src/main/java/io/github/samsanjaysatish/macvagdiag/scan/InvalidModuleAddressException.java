package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Optional;

/**
 * A module address from a caller was malformed or isn't a known module. Thrown before anything is built or sent.
 * The message says what is accepted, so it can be passed to an AI agent or shown to the user as it is.
 */
public final class InvalidModuleAddressException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String input;

    InvalidModuleAddressException(String input, String message) {
        super(message);
        this.input = input;
    }

    /** The rejected text, or empty if none was given. Untrusted: don't echo it without escaping. */
    public Optional<String> input() {
        return Optional.ofNullable(input);
    }
}
