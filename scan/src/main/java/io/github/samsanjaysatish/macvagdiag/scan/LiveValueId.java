package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One live value: a field of a KWP measuring-block group, written {@code 01:007.3} (module 01, group 007,
 * field 3), the way VCDS numbers groups (decimal) and fields (1..4).
 *
 * @param module the module
 * @param group 1..255
 * @param field 1..4
 */
public record LiveValueId(ModuleAddress module, int group, int field) {

    public static final int MAX_GROUP = 255;
    public static final int MAX_FIELD = 4;
    private static final Pattern FORM = Pattern.compile("([0-9A-Fa-f]{2}):([0-9]{3})\\.([1-4])");

    public LiveValueId {
        Objects.requireNonNull(module, "module");
        if (group < 1 || group > MAX_GROUP) {
            throw new IllegalArgumentException("group must be 1..255: " + group);
        }
        if (field < 1 || field > MAX_FIELD) {
            throw new IllegalArgumentException("field must be 1..4: " + field);
        }
    }

    /**
     * Parses {@code 01:007.3} exactly: two hex digits naming a known module, {@code :}, three decimal digits
     * {@code 001}..{@code 255}, {@code .}, one digit {@code 1}..{@code 4}. Empty for anything else.
     */
    public static Optional<LiveValueId> tryParse(String text) {
        if (text == null || text.length() != 8) {
            return Optional.empty();
        }
        Matcher m = FORM.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<ModuleAddress> module = ModuleAddress.tryParse(m.group(1));
        int group = Integer.parseInt(m.group(2));
        if (module.isEmpty() || group < 1 || group > MAX_GROUP) {
            return Optional.empty();
        }
        return Optional.of(new LiveValueId(module.get(), group, Integer.parseInt(m.group(3))));
    }

    /** {@code 01:007.3}. */
    @Override
    public String toString() {
        return String.format("%s:%03d.%d", module.hex(), group, field);
    }
}
