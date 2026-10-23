package io.github.samsanjaysatish.macvagdiag.can;

import java.io.IOException;

/** A capture file line that is not valid candump log format. The message starts with the line number. */
public final class CaptureFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int lineNumber;

    CaptureFormatException(int lineNumber, String problem, String line) {
        super("line " + lineNumber + ": " + problem + ": \"" + SlcanCodec.printable(line) + "\"");
        this.lineNumber = lineNumber;
    }

    /** 1-based line number of the bad line. */
    public int lineNumber() {
        return lineNumber;
    }
}
