package io.github.samsanjaysatish.macvagdiag.scan;

/**
 * Backstop for the "never show the VIN" rule (root {@code CLAUDE.md}; user's decision 2026-11-19 for live data):
 * a live-data response containing 17 bytes in a row that are VIN characters ({@code 0-9}, {@code A-Z} without
 * {@code I O Q}, ASCII) is dropped. Measuring-block triplets are numbers, not text, so a real reading never looks
 * like that; a module that puts its VIN in a group would.
 */
final class VinShape {

    static final int VIN_LENGTH = 17;

    private VinShape() {
    }

    static boolean contains(byte[] bytes) {
        int run = 0;
        for (byte b : bytes) {
            run = isVinChar(b) ? run + 1 : 0;
            if (run >= VIN_LENGTH) {
                return true;
            }
        }
        return false;
    }

    static boolean isVinChar(byte b) {
        int c = b & 0xFF;
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'O' && c != 'Q');
    }
}
