package io.github.samsanjaysatish.macvagdiag.tools;

import io.github.samsanjaysatish.macvagdiag.tools.Redactions.Hit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the VIN (and, on request, other identifying fields) in a VCDS text log. Text in, text out; every
 * replacement keeps the length, so line structure and column alignment stay as VCDS wrote them.
 *
 * <p>The VIN is always masked ({@link Redactions}). A non-empty license plate or repair order is always masked
 * too: like the VIN, it names the car or the job. The other fields are reported and kept unless asked for,
 * since some tests may want them:
 * <ul>
 *   <li>{@code VCID} (per module) and {@code VINID} (engine): values VCDS derives itself; they never appear on
 *       the bus. Masked to zeros.</li>
 *   <li>{@code Serial number}: module and sensor serial numbers. They also appear on the bus in identification
 *       responses; mask them there with {@code sanitize-capture --mask}. Digits become 0, letters X.</li>
 *   <li>Workshop codes ({@code WSC 00066 000 00000}: workshop, importer, equipment of whoever last coded the
 *       module). Digits become 0.</li>
 * </ul>
 * Every masked value is also masked wherever else it appears in the log (as a standalone token for serials,
 * plates and repair orders; anywhere for VCID and VINID).
 */
final class VcdsLogSanitizer {

    enum Field {
        LICENSE_PLATE("License Plate", Pattern.compile("License Plate:[ \\t]*([^\\r\\n]*?)[ \\t]*$", Pattern.MULTILINE),
                null, true, true),
        REPAIR_ORDER("Repair Order", Pattern.compile("Repair Order:[ \\t]*([^\\r\\n]*?)[ \\t]*$", Pattern.MULTILINE),
                null, true, true),
        VCID("VCID", Pattern.compile("\\bVCID:[ \\t]*([0-9A-Fa-f]+(?:-[0-9A-Fa-f]+)?)"), "--mask-vcid", false, false),
        VINID("VINID", Pattern.compile("\\bVINID:[ \\t]*([0-9A-Fa-f]+)"), "--mask-vinid", false, false),
        SERIAL("Serial number", Pattern.compile("Serial number:[ \\t]*([^ \\t\\r\\n]+)"), "--mask-serials", true,
                false),
        SHOP_CODE("Workshop code (WSC)", Pattern.compile("\\bWSC[ \\t]+(\\d+[ \\t]+\\d+[ \\t]+\\d+)"),
                "--mask-shop-codes", false, false);

        private final String label;
        private final Pattern pattern;
        private final String option;
        private final boolean tokenOnly;
        private final boolean alwaysMasked;

        Field(String label, Pattern pattern, String option, boolean tokenOnly, boolean alwaysMasked) {
            this.label = label;
            this.pattern = pattern;
            this.option = option;
            this.tokenOnly = tokenOnly;
            this.alwaysMasked = alwaysMasked;
        }

        String label() {
            return label;
        }

        /** The option that masks it; empty for fields that are always masked. */
        Optional<String> option() {
            return Optional.ofNullable(option);
        }

        boolean alwaysMasked() {
            return alwaysMasked;
        }

        String replacement(String value) {
            return this == VCID || this == VINID ? value.replaceAll("[0-9A-Fa-f]", "0") : Redactions.scramble(value);
        }
    }

    /** A field value seen at {@code line} (1-based). */
    record FieldHit(Field field, int line, boolean masked) {
    }

    /** A masked VIN or {@code --mask} string at {@code line}. */
    record TextHit(Redactions.Kind kind, int line) {
    }

    /**
     * @param otherPlaces masked field values found again outside their field
     */
    record Result(String output, List<TextHit> textHits, List<FieldHit> fields, int otherPlaces) {
    }

    private record Value(Field field, String text) {
    }

    private VcdsLogSanitizer() {
    }

    /** Fields always masked plus the ones asked for. */
    static Set<Field> effective(Set<Field> requested) {
        Set<Field> out = EnumSet.noneOf(Field.class);
        out.addAll(requested);
        for (Field f : Field.values()) {
            if (f.alwaysMasked()) {
                out.add(f);
            }
        }
        return out;
    }

    static Result sanitize(String input, Redactions redactions, Set<Field> requested) {
        Set<Field> mask = effective(requested);
        int[] lineStarts = lineStarts(input);
        List<Hit> hits = new ArrayList<>();
        String text = redactions.maskText(input, hits);
        List<TextHit> textHits = hits.stream().map(h -> new TextHit(h.needle().kind(), line(lineStarts, h.offset())))
                .toList();

        char[] chars = text.toCharArray();
        List<FieldHit> fields = new ArrayList<>();
        Set<Value> values = new LinkedHashSet<>();
        for (Field f : Field.values()) {
            Matcher m = f.pattern.matcher(text);
            while (m.find()) {
                String value = m.group(1);
                if (value.isEmpty() || value.chars().allMatch(c -> c == '-')) {
                    continue;
                }
                boolean masked = mask.contains(f);
                fields.add(new FieldHit(f, line(lineStarts, m.start(1)), masked));
                if (masked) {
                    String r = f.replacement(value);
                    for (int k = 0; k < value.length(); k++) {
                        chars[m.start(1) + k] = r.charAt(k);
                    }
                    if (f != Field.SHOP_CODE && !alreadyMasked(f, value)) {
                        values.add(new Value(f, value));
                    }
                }
            }
        }
        int otherPlaces = 0;
        for (Value v : values) {
            int i = 0;
            while ((i = indexOf(chars, v, i)) >= 0) {
                String r = v.field().replacement(v.text());
                for (int k = 0; k < r.length(); k++) {
                    chars[i + k] = r.charAt(k);
                }
                otherPlaces++;
                i += r.length();
            }
        }
        fields.sort(java.util.Comparator.comparingInt(FieldHit::line));
        return new Result(new String(chars), textHits, List.copyOf(fields), otherPlaces);
    }

    /** Independent check of a result (run on what was written to disk). */
    static void verify(String input, String output, Redactions redactions, Set<Field> requested)
            throws SanitizeException {
        Set<Field> mask = effective(requested);
        List<String> problems = new ArrayList<>();
        if (input.length() != output.length()
                || !Arrays.equals(lineStarts(input), lineStarts(output))) {
            problems.add("the output's length or line structure differs from the input");
        }
        int[] outStarts = lineStarts(output);
        for (Hit h : redactions.findText(output)) {
            problems.add("line " + line(outStarts, h.offset()) + ": " + h.needle().kind().label() + " remains");
        }
        char[] chars = output.toCharArray();
        for (Field f : mask) {
            Matcher m = f.pattern.matcher(input);
            while (m.find()) {
                String value = m.group(1);
                if (value.isEmpty() || value.chars().allMatch(c -> c == '-')) {
                    continue;
                }
                if (f == Field.SHOP_CODE || alreadyMasked(f, value)) {
                    continue;
                }
                int i = indexOf(chars, new Value(f, value), 0);
                if (i >= 0) {
                    problems.add("line " + line(outStarts, i) + ": a " + f.label() + " value remains");
                }
            }
        }
        if (mask.contains(Field.SHOP_CODE)) {
            Matcher m = Field.SHOP_CODE.pattern.matcher(output);
            while (m.find()) {
                if (m.group(1).chars().anyMatch(c -> c >= '1' && c <= '9')) {
                    problems.add("line " + line(outStarts, m.start(1)) + ": a workshop code remains");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new SanitizeException("verification failed, " + problems.size() + " problem(s):\n  "
                    + String.join("\n  ", new LinkedHashSet<>(problems)));
        }
    }

    /**
     * A value that is its own replacement (e.g. a serial number of all zeros) hides nothing, and searching for it
     * would match every other masked value.
     */
    private static boolean alreadyMasked(Field f, String value) {
        return f.replacement(value).equalsIgnoreCase(value);
    }

    /** Case-insensitive; standalone token for fields with the token rule. A masked value never matches itself. */
    private static int indexOf(char[] chars, Value v, int from) {
        String find = v.text().toUpperCase(Locale.ROOT);
        int n = find.length();
        outer:
        for (int i = Math.max(0, from); i + n <= chars.length; i++) {
            for (int k = 0; k < n; k++) {
                if (Character.toUpperCase(chars[i + k]) != find.charAt(k)) {
                    continue outer;
                }
            }
            if (v.field().tokenOnly && (Redactions.isAlnum(chars, i - 1) || Redactions.isAlnum(chars, i + n))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 1-based line of {@code offset}. */
    private static int line(int[] starts, int offset) {
        int i = Arrays.binarySearch(starts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }
}
