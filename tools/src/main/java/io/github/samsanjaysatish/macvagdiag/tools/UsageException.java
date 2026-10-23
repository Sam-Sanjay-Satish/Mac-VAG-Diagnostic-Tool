package io.github.samsanjaysatish.macvagdiag.tools;

/** Bad command-line arguments. The message says what is wrong and is shown to the user as is. */
final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
