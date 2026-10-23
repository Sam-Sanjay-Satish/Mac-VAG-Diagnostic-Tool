package io.github.samsanjaysatish.macvagdiag.diag;

/** Small shared rules for the services. */
final class Reads {

    private Reads() {
    }

    /**
     * For operations made of several requests (identification, freeze frame): a {@link DiagResult.Negative} or
     * {@link DiagResult.Malformed} answer to one part is noted and the operation goes on with the next part.
     * Anything else (busy, timeout, pending cap, transport failure) stops the operation and is returned, so
     * {@code scan} can apply its back-off to the whole operation.
     */
    static boolean partFailureContinues(DiagResult<?> result) {
        return result instanceof DiagResult.Negative<?> || result instanceof DiagResult.Malformed<?>;
    }

    /** Checks the positive response echoes {@code expected} at {@code index}. */
    static boolean echoes(RawBytes response, int index, int expected) {
        return response.length() > index && response.byteAt(index) == expected;
    }
}
