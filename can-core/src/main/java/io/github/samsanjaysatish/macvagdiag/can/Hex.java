package io.github.samsanjaysatish.macvagdiag.can;

import java.util.HexFormat;

/**
 * Hex helpers that write bytes the way they appear on the bus: uppercase, space separated
 * ({@code 02 10 03}).
 */
public final class Hex {

    private static final HexFormat SPACED = HexFormat.ofDelimiter(" ").withUpperCase();
    private static final HexFormat COMPACT = HexFormat.of().withUpperCase();

    private Hex() {
    }

    /** {@code 02 10 03}; empty string for no bytes. */
    public static String format(byte[] bytes) {
        return SPACED.formatHex(bytes);
    }

    /** {@code 021003}; empty string for no bytes. */
    public static String formatCompact(byte[] bytes) {
        return COMPACT.formatHex(bytes);
    }

    /**
     * Parses hex bytes, upper- or lowercase, with or without spaces between bytes:
     * {@code "02 10 03"}, {@code "021003"}.
     *
     * @throws IllegalArgumentException on an odd number of digits or a non-hex character
     */
    public static byte[] parse(String hex) {
        String compact = hex.replace(" ", "");
        if (compact.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd number of hex digits: \"" + hex + "\"");
        }
        for (int i = 0; i < compact.length(); i++) {
            if (Character.digit(compact.charAt(i), 16) < 0) {
                throw new IllegalArgumentException("Not a hex digit '" + compact.charAt(i) + "' in \"" + hex + "\"");
            }
        }
        return HexFormat.of().parseHex(compact);
    }

    /** Value of one hex digit, or -1 if {@code c} is not {@code 0-9 a-f A-F}. */
    static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }
}
