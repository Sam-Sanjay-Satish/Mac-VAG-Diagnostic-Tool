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
     * A VAG KWP2000 code as VCDS shows it: the decimal of the 16-bit value, 5 digits, e.g. gateway {@code 05 18}
     * = {@code 01304} (confirmed against the 2026-11-13 VCDS log).
     */
    public static String vagFiveDigit(int code16) {
        return String.format("%05d", check16(code16));
    }

    /**
     * The same decimal, 6 digits, as VCDS shows codes that have a P-code: engine {@code 2B A6} = {@code 011174}
     * (all 12 engine codes of the 2026-11-13 VCDS log). UNCONFIRMED that the P-code is what decides the width.
     */
    public static String vagSixDigit(int code16) {
        return String.format("%06d", check16(code16));
    }

    /**
     * A UDS DTC as VCDS numbers it: the decimal of the 24-bit value, at least 4 digits, e.g. {@code 90 3E 12} =
     * {@code 9453074}, {@code 00 00 16} = {@code 0022} (all 5 UDS codes of the 2026-11-13 VCDS log). The
     * 4-digit minimum is from that one short sample.
     */
    public static String vagUdsNumber(int code24) {
        if (code24 < 0 || code24 > 0xFFFFFF) {
            throw new IllegalArgumentException("Not a 24-bit code: " + code24);
        }
        return String.format("%04d", code24);
    }

    private static int check16(int code16) {
        if (code16 < 0 || code16 > 0xFFFF) {
            throw new IllegalArgumentException("Not a 16-bit code: " + code16);
        }
        return code16;
    }

    private static void checkByte(int b) {
        if (b < 0 || b > 0xFF) {
            throw new IllegalArgumentException("Not a byte: " + b);
        }
    }
}
