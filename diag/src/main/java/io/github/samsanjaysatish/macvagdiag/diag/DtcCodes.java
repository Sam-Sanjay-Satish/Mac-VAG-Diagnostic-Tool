package io.github.samsanjaysatish.macvagdiag.diag;

/** Display formats for DTC codes. */
public final class DtcCodes {

    private static final char[] SAE_LETTERS = {'P', 'C', 'B', 'U'};

    private DtcCodes() {
    }

    /**
     * SAE J2012 / ISO 15031-6 code from two bytes: bits 7..6 of the high byte give the letter (00 P powertrain,
     * 01 C chassis, 10 B body, 11 U network), bits 5..4 the first digit (0..3), bits 3..0 the second digit, and the
     * low byte the last two digits in hex. E.g. {@code 04 01} = {@code P0401}, {@code C4 01} = {@code U0401}.
     */
    public static String saeJ2012(int highByte, int lowByte) {
        checkByte(highByte);
        checkByte(lowByte);
        return String.format("%c%d%X%02X", SAE_LETTERS[highByte >> 6], (highByte >> 4) & 0x3, highByte & 0xF, lowByte);
    }

    /**
     * UNCONFIRMED: a VAG KWP2000 code as VCDS shows it, assumed to be the 5-digit decimal of the 16-bit value,
     * e.g. {@code 41 2C} = {@code 16684}. Check against the VCDS log.
     */
    public static String vagFiveDigit(int code16) {
        if (code16 < 0 || code16 > 0xFFFF) {
            throw new IllegalArgumentException("Not a 16-bit code: " + code16);
        }
        return String.format("%05d", code16);
    }

    private static void checkByte(int b) {
        if (b < 0 || b > 0xFF) {
            throw new IllegalArgumentException("Not a byte: " + b);
        }
    }
}
