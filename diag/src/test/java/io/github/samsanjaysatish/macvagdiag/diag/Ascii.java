package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import java.nio.charset.StandardCharsets;

/** Test helper: text as bus-style hex. */
final class Ascii {

    /** A made-up, VIN-shaped test value (17 characters). Not a real car's VIN. */

    private Ascii() {
    }

    static String hex(String text) {
        return Hex.format(text.getBytes(StandardCharsets.US_ASCII));
    }
}
