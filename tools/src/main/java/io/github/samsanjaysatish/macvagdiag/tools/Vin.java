package io.github.samsanjaysatish.macvagdiag.tools;

/** VIN helpers for the fixture sanitizer: character set, check digit and the fixed replacement VIN. */
final class Vin {

    static final int LENGTH = 17;

    /**
     * Replaces the real VIN in sanitized captures and logs. It keeps the WMI {@code 3VW} (VW Mexico) and the
     * model year {@code D} (2013) so decoders still see a plausible Jetta VIN; everything that identifies a car
     * is zero. Valid character set and check digit ({@code X} at position 9).
     */
    static final String FAKE = "3VW00000XD0000000";

    private static final int CHECK_DIGIT_INDEX = 8;
    private static final int CHECK_MODULUS = 11;
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};

    private Vin() {
    }

    /** VIN characters: digits and capital letters except I, O and Q. */
    static boolean isVinChar(int c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' && c != 'I' && c != 'O' && c != 'Q';
    }

    /** 17 VIN characters (uppercase). Says nothing about the check digit, which European VINs don't use. */
    static boolean isWellFormed(String s) {
        return s.length() == LENGTH && s.chars().allMatch(Vin::isVinChar);
    }

    /** The North American check digit (ISO 3779 / 49 CFR 565) for a well-formed VIN. */
    static char checkDigit(String vin) {
        if (!isWellFormed(vin)) {
            throw new IllegalArgumentException("not a well-formed VIN");
        }
        int sum = 0;
        for (int i = 0; i < LENGTH; i++) {
            sum += transliterate(vin.charAt(i)) * WEIGHTS[i];
        }
        int rest = sum % CHECK_MODULUS;
        return rest == 10 ? 'X' : (char) ('0' + rest);
    }

    static boolean hasValidCheckDigit(String vin) {
        return isWellFormed(vin) && vin.charAt(CHECK_DIGIT_INDEX) == checkDigit(vin);
    }

    private static int transliterate(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c <= 'H') {
            return c - 'A' + 1;   // A..H = 1..8
        }
        if (c <= 'N') {
            return c - 'J' + 1;   // J..N = 1..5
        }
        if (c == 'P') {
            return 7;
        }
        if (c == 'R') {
            return 9;
        }
        return c - 'S' + 2;       // S..Z = 2..9
    }
}
