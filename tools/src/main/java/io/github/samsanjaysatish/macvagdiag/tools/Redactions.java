package io.github.samsanjaysatish.macvagdiag.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the sanitizer masks and what its verification looks for. All matching is ASCII case-insensitive.
 *
 * <p>For a real VIN it masks:
 * <ul>
 *   <li>the full VIN, replaced by {@link Vin#FAKE};</li>
 *   <li>every substring of {@value #MIN_MASKED_PART} or more characters, replaced by the fake VIN's characters
 *       at the same offset. That covers a VIN cut short by a message or capture boundary and the "last 8"
 *       form (model year, plant, serial) some modules report;</li>
 *   <li>the last {@value #SERIAL_LENGTH} characters (the serial number). In binary payloads anywhere; in text
 *       only as a standalone token, so a mileage or part number that happens to contain the digits is left
 *       alone. Text verification then fails on any standalone occurrence that remains.</li>
 * </ul>
 * Verification is stricter than masking: it also looks for every {@value #MIN_VERIFIED_PART}-character
 * substring, so a fragment that the reassembly missed fails loudly instead of slipping through.
 *
 * <p>Extra strings ({@code --mask}) are masked wherever they appear, keeping their length: digits become
 * {@code 0}, letters {@code X}, everything else stays.
 */
final class Redactions {

    static final int MIN_MASKED_PART = 8;
    static final int MIN_VERIFIED_PART = 7;
    static final int SERIAL_LENGTH = 6;
    static final int MIN_EXTRA_LENGTH = 6;

    enum Kind {
        VIN("VIN"),
        VIN_PART("part of the VIN"),
        VIN_SERIAL("VIN serial number (last 6)"),
        EXTRA("--mask string");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * One string to find (stored uppercase) and its same-length replacement.
     *
     * @param tokenOnlyInText in text, match only when not next to another letter or digit
     */
    record Needle(String find, String replacement, Kind kind, boolean tokenOnlyInText) {

        Needle {
            if (find.length() != replacement.length()) {
                throw new IllegalArgumentException("replacement must keep the length");
            }
            find = find.toUpperCase(Locale.ROOT);
        }

        int length() {
            return find.length();
        }
    }

    /** A match at {@code offset} in a byte stream or text. */
    record Hit(int offset, Needle needle) {
    }

    private final Optional<String> vin;
    private final List<Needle> mask;
    private final List<Needle> verify;

    private Redactions(Optional<String> vin, List<Needle> mask, List<Needle> verify) {
        this.vin = vin;
        this.mask = List.copyOf(mask);
        this.verify = List.copyOf(verify);
    }

    /**
     * @param vin the real VIN (any case), or empty when only {@code extras} are masked
     * @param extras more strings to mask (printable ASCII, at least {@value #MIN_EXTRA_LENGTH} characters)
     * @throws IllegalArgumentException with a message for the user (never containing the VIN)
     */
    static Redactions of(Optional<String> vin, List<String> extras) {
        List<Needle> mask = new ArrayList<>();
        List<Needle> verify = new ArrayList<>();
        Optional<String> upperVin = vin.map(v -> v.toUpperCase(Locale.ROOT));
        upperVin.ifPresent(v -> addVin(v, mask, verify));
        for (String extra : extras) {
            if (extra.length() < MIN_EXTRA_LENGTH) {
                throw new IllegalArgumentException("--mask strings need at least " + MIN_EXTRA_LENGTH
                        + " characters, so random bytes aren't masked by accident");
            }
            if (!extra.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
                throw new IllegalArgumentException("--mask strings must be printable ASCII");
            }
            String replacement = scramble(extra);
            if (replacement.equalsIgnoreCase(extra)) {
                throw new IllegalArgumentException("a --mask string is already all zeros/X; nothing to mask");
            }
            Needle needle = new Needle(extra, replacement, Kind.EXTRA, false);
            mask.add(needle);
            verify.add(needle);
        }
        Comparator<Needle> longestFirst = Comparator.comparingInt(Needle::length).reversed();
        mask.sort(longestFirst);
        verify.sort(longestFirst);
        return new Redactions(upperVin, mask, verify);
    }

    private static void addVin(String vin, List<Needle> mask, List<Needle> verify) {
        if (!Vin.isWellFormed(vin)) {
            throw new IllegalArgumentException("the VIN must be 17 characters: digits and letters except I, O, Q");
        }
        if (vin.equals(Vin.FAKE)) {
            throw new IllegalArgumentException("the VIN is already the replacement VIN " + Vin.FAKE);
        }
        Map<String, Needle> verifyParts = new LinkedHashMap<>();
        for (int length = Vin.LENGTH; length >= MIN_VERIFIED_PART; length--) {
            for (int start = 0; start + length <= Vin.LENGTH; start++) {
                String part = vin.substring(start, start + length);
                Kind kind = length == Vin.LENGTH ? Kind.VIN : Kind.VIN_PART;
                verifyParts.putIfAbsent(part,
                        new Needle(part, Vin.FAKE.substring(start, start + length), kind, false));
            }
        }
        String serial = vin.substring(Vin.LENGTH - SERIAL_LENGTH);
        Needle serialNeedle = new Needle(serial, Vin.FAKE.substring(Vin.LENGTH - SERIAL_LENGTH), Kind.VIN_SERIAL,
                true);
        for (Needle n : verifyParts.values()) {
            if (Vin.FAKE.contains(n.find())) {
                throw new IllegalArgumentException("the VIN shares " + n.length()
                        + " characters in a row with the replacement VIN; masking couldn't remove them");
            }
        }
        if (Vin.FAKE.contains(serial)) {
            throw new IllegalArgumentException("the VIN's serial number also appears in the replacement VIN");
        }
        for (Needle n : verifyParts.values()) {
            verify.add(n);
            if (n.length() >= MIN_MASKED_PART) {
                mask.add(n);
            }
        }
        if (!verifyParts.containsKey(serial)) {
            mask.add(serialNeedle);
            verify.add(serialNeedle);
        }
    }

    /** Same length; digits become {@code 0}, ASCII letters {@code X}, everything else stays. */
    static String scramble(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append('0');
            } else if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                b.append('X');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    Optional<String> vin() {
        return vin;
    }

    boolean isEmpty() {
        return mask.isEmpty();
    }

    List<Needle> maskNeedles() {
        return mask;
    }

    List<Needle> verifyNeedles() {
        return verify;
    }

    // ---- bytes ----

    /** Masks every occurrence in {@code stream}, longest needles first, without overlaps. Returns the hits. */
    List<Hit> maskBytes(byte[] stream) {
        List<Hit> hits = new ArrayList<>();
        for (Needle n : mask) {
            byte[] replacement = n.replacement().getBytes(StandardCharsets.US_ASCII);
            int i = 0;
            while ((i = indexOf(stream, n.find(), i)) >= 0) {
                boolean lower = isLowercase(stream, i, n.length());
                for (int k = 0; k < n.length(); k++) {
                    byte r = replacement[k];
                    stream[i + k] = lower && r >= 'A' && r <= 'Z' ? (byte) (r + ('a' - 'A')) : r;
                }
                hits.add(new Hit(i, n));
                i += n.length();
            }
        }
        hits.sort(Comparator.comparingInt(Hit::offset));
        return hits;
    }

    /** Every verification needle found in {@code stream} (nothing is changed). */
    List<Hit> findBytes(byte[] stream) {
        List<Hit> hits = new ArrayList<>();
        for (Needle n : verify) {
            int i = 0;
            while ((i = indexOf(stream, n.find(), i)) >= 0) {
                hits.add(new Hit(i, n));
                i++;
            }
        }
        return hits;
    }

    private static int indexOf(byte[] stream, String upperNeedle, int from) {
        int n = upperNeedle.length();
        outer:
        for (int i = Math.max(0, from); i + n <= stream.length; i++) {
            for (int k = 0; k < n; k++) {
                if (upper(stream[i + k] & 0xFF) != upperNeedle.charAt(k)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int upper(int c) {
        return c >= 'a' && c <= 'z' ? c - ('a' - 'A') : c;
    }

    /** A match is written in lowercase if it has lowercase letters and no uppercase ones. */
    private static boolean isLowercase(byte[] b, int from, int length) {
        boolean lower = false;
        for (int k = from; k < from + length; k++) {
            if (b[k] >= 'A' && b[k] <= 'Z') {
                return false;
            }
            lower |= b[k] >= 'a' && b[k] <= 'z';
        }
        return lower;
    }

    private static boolean isLowercase(char[] c, int from, int length) {
        boolean lower = false;
        for (int k = from; k < from + length; k++) {
            if (c[k] >= 'A' && c[k] <= 'Z') {
                return false;
            }
            lower |= c[k] >= 'a' && c[k] <= 'z';
        }
        return lower;
    }

    // ---- text ----

    /** Masks {@code text} like {@link #maskBytes}, with the token rule for the serial. Hits are offsets in it. */
    String maskText(String text, List<Hit> hits) {
        char[] chars = text.toCharArray();
        for (Needle n : mask) {
            int i = 0;
            while ((i = indexOf(chars, n, i)) >= 0) {
                boolean lower = isLowercase(chars, i, n.length());
                for (int k = 0; k < n.length(); k++) {
                    char r = n.replacement().charAt(k);
                    chars[i + k] = lower ? Character.toLowerCase(r) : r;
                }
                hits.add(new Hit(i, n));
                i += n.length();
            }
        }
        hits.sort(Comparator.comparingInt(Hit::offset));
        return new String(chars);
    }

    /** Every verification needle in {@code text}, with the token rule for the serial. */
    List<Hit> findText(String text) {
        char[] chars = text.toCharArray();
        List<Hit> hits = new ArrayList<>();
        for (Needle n : verify) {
            int i = 0;
            while ((i = indexOf(chars, n, i)) >= 0) {
                hits.add(new Hit(i, n));
                i++;
            }
        }
        return hits;
    }

    private static int indexOf(char[] chars, Needle needle, int from) {
        String find = needle.find();
        int n = find.length();
        outer:
        for (int i = Math.max(0, from); i + n <= chars.length; i++) {
            for (int k = 0; k < n; k++) {
                if (upper(chars[i + k]) != find.charAt(k)) {
                    continue outer;
                }
            }
            if (needle.tokenOnlyInText() && (isAlnum(chars, i - 1) || isAlnum(chars, i + n))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    static boolean isAlnum(char[] chars, int i) {
        if (i < 0 || i >= chars.length) {
            return false;
        }
        char c = chars[i];
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }
}
