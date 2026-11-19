package io.github.samsanjaysatish.macvagdiag.labels;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A VW part number split the way label files are named: a 9-character root ({@code 03L997030}) and a suffix of
 * letters ({@code M}, may be empty). Parsed from what a module reports ({@code "03L997030M  "} from KWP
 * {@code 1A 9B}, {@code "03L 997 030 M"} as VCDS prints it, or {@code 03L-997-030-M}).
 *
 * @param root the first 9 characters, upper case, no separators
 * @param suffix the rest, upper case, may be empty
 */
public record PartNumber(String root, String suffix) {

    static final int ROOT_LENGTH = 9;
    static final int MAX_SUFFIX_LENGTH = 4;

    public PartNumber {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(suffix, "suffix");
        if (root.length() != ROOT_LENGTH || !alphanumeric(root) || suffix.length() > MAX_SUFFIX_LENGTH
                || !alphanumeric(suffix)) {
            throw new IllegalArgumentException("not a part number: " + root + " " + suffix);
        }
    }

    /** Parses a reported part number; empty if it isn't one (too short, odd characters). */
    public static Optional<PartNumber> parse(String reported) {
        if (reported == null) {
            return Optional.empty();
        }
        String s = reported.replace(" ", "").replace("-", "").strip().toUpperCase(Locale.ROOT);
        if (s.length() < ROOT_LENGTH || s.length() > ROOT_LENGTH + MAX_SUFFIX_LENGTH || !alphanumeric(s)) {
            return Optional.empty();
        }
        return Optional.of(new PartNumber(s.substring(0, ROOT_LENGTH), s.substring(ROOT_LENGTH)));
    }

    /** {@code 03L-997-030}: the label-file form of the root. */
    public String dashedRoot() {
        return root.substring(0, 3) + "-" + root.substring(3, 6) + "-" + root.substring(6, 9);
    }

    /** {@code 03L-997-030-M}, or {@code 03L-997-030} without a suffix: how redirect patterns write it. */
    public String dashed() {
        return suffix.isEmpty() ? dashedRoot() : dashedRoot() + "-" + suffix;
    }

    /** The first two characters ({@code 03}, {@code 1K}), the {@code AA} of {@code AA-BB.LBL}. */
    public String prefix() {
        return root.substring(0, 2);
    }

    /** {@code 03L 997 030 M}, as VCDS prints it. */
    @Override
    public String toString() {
        String r = root.substring(0, 3) + " " + root.substring(3, 6) + " " + root.substring(6, 9);
        return suffix.isEmpty() ? r : r + " " + suffix;
    }

    private static boolean alphanumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }
}
