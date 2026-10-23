package io.github.samsanjaysatish.macvagdiag.can;

/** A line from an slcan adapter that is not valid slcan. The message quotes the line. */
public final class SlcanFormatException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    SlcanFormatException(String line, String problem) {
        super("Malformed slcan line \"" + SlcanCodec.printable(line) + "\": " + problem);
    }
}
