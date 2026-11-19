package io.github.samsanjaysatish.macvagdiag.labels;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One parsed label file (VCDS {@code .lbl} text format): its measuring-block lines and its redirects. Everything
 * else in the file (basic settings {@code Bnnn}, adaptation {@code Annn}, coding {@code Cnn} / {@code LC},
 * security access {@code Snn}, output tests {@code Tnnnn}, service reset {@code Rx}, comments and so on) is
 * ignored: it never reaches the catalog.
 *
 * <p>Line formats used (checked against our set of 1,964 decoded files, 2026-11-19):
 * <ul>
 *   <li>{@code GGG,0,Title}: a measuring group's title; {@code GGG,F,Name,Detail,Help} for field F = 1..4. The
 *       group is decimal, 1 to 4 digits ({@code 001}, {@code 8}, {@code 0033}). Help lines are separated by a
 *       literal {@code \n}. Fields above 4 (a few files go to 10) aren't shown by VCDS and are ignored. There is
 *       no CSV quoting: {@code "} is an ordinary character.</li>
 *   <li>{@code REDIRECT,<file>,<suffix>,<suffix>,...}: suffix table (Ross-Tech's original method).</li>
 *   <li>{@code REDIRECT,<file>,<part number pattern>[,HW]}: {@code ?} matches any one character; {@code HW}
 *       matches the pattern against the hardware part number. Other qualifiers ({@code SL}) are skipped.</li>
 * </ul>
 * A bare {@code REDIRECT,<file>} line is ignored: the one in our set ({@code 03-01.lbl} line 48) is a truncated
 * line, and VCDS ignores it too (it shows {@code 03L-906-022-CBE} for the target car's engine, a later line).
 * Text after {@code ;} on a redirect line is a comment.
 */
final class LabelFile {

    /** Label files are Windows-1252 text ({@code °} is {@code B0}). */
    static final Charset CHARSET = Charset.forName("windows-1252");

    private static final Pattern MEASURING_LINE = Pattern.compile("^(\\d{1,4}),(\\d{1,2}),(.*)$");
    private static final String REDIRECT = "REDIRECT";
    /** Help lines kept as specs (user's decision 2026-11-19: names and specs only). */
    private static final Pattern SPEC_LINE = Pattern.compile("(?i)^(range|specification)\\b.*");

    /** One redirect line: a suffix list (non-empty) or a pattern, never both. */
    record Redirect(String target, List<String> suffixes, Optional<String> pattern, boolean hardware) {

        Redirect {
            Objects.requireNonNull(target, "target");
            suffixes = List.copyOf(suffixes);
            Objects.requireNonNull(pattern, "pattern");
            if (suffixes.isEmpty() == pattern.isEmpty()) {
                throw new IllegalArgumentException("a redirect has either suffixes or a pattern");
            }
        }

        boolean matches(PartNumber part, Optional<PartNumber> hardwarePart) {
            if (!suffixes.isEmpty()) {
                return !part.suffix().isEmpty() && suffixes.contains(part.suffix());
            }
            Optional<PartNumber> subject = hardware ? hardwarePart : Optional.of(part);
            return subject.isPresent() && patternMatches(pattern.get(), subject.get().dashed());
        }
    }

    private final String name;
    private final List<GroupLabel> groups;
    private final List<Redirect> redirects;

    private LabelFile(String name, List<GroupLabel> groups, List<Redirect> redirects) {
        this.name = name;
        this.groups = List.copyOf(groups);
        this.redirects = List.copyOf(redirects);
    }

    String name() {
        return name;
    }

    List<GroupLabel> groups() {
        return groups;
    }

    List<Redirect> redirects() {
        return redirects;
    }

    /** The first redirect that applies to this module, in file order. */
    Optional<Redirect> redirectFor(PartNumber part, Optional<PartNumber> hardwarePart) {
        return redirects.stream().filter(r -> r.matches(part, hardwarePart)).findFirst();
    }

    /**
     * {@code ?} matches any one character; a pattern longer than the part number matches only if the rest is
     * {@code ?} and {@code -} (so {@code 4K0-907-160-???} matches {@code 4K0-907-160-A} and {@code 4K0-907-160}).
     * Case is ignored.
     */
    static boolean patternMatches(String pattern, String dashedPart) {
        String p = pattern.strip().toUpperCase(Locale.ROOT);
        String s = dashedPart.toUpperCase(Locale.ROOT);
        if (s.length() > p.length()) {
            return false;
        }
        for (int i = 0; i < p.length(); i++) {
            char pc = p.charAt(i);
            if (i >= s.length()) {
                if (pc != '?' && pc != '-') {
                    return false;
                }
            } else if (pc != '?' && pc != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Parses the whole text of one file. Never throws for odd content: lines it doesn't understand are skipped. */
    static LabelFile parse(String name, String text) {
        Map<Integer, String> titles = new LinkedHashMap<>();
        Map<Integer, TreeMap<Integer, FieldLabel>> fields = new LinkedHashMap<>();
        List<Redirect> redirects = new ArrayList<>();
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.regionMatches(true, 0, REDIRECT + ",", 0, REDIRECT.length() + 1)) {
                parseRedirect(line).ifPresent(redirects::add);
                continue;
            }
            Matcher m = MEASURING_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int group = Integer.parseInt(m.group(1));
            int field = Integer.parseInt(m.group(2));
            if (group < 1 || group > 0xFF || field > GroupLabel.FIELDS) {
                continue;
            }
            String rest = m.group(3);
            if (field == 0) {
                titles.putIfAbsent(group, stripTrailingCommas(rest));
                fields.computeIfAbsent(group, g -> new TreeMap<>());
                continue;
            }
            String[] cols = rest.split(",", 3);
            String fieldName = cols[0].strip();
            String detail = cols.length > 1 ? cols[1].strip() : "";
            List<String> specs = cols.length > 2 ? specs(cols[2]) : List.of();
            fields.computeIfAbsent(group, g -> new TreeMap<>())
                    .putIfAbsent(field, new FieldLabel(field, fieldName, detail, specs));
        }
        List<GroupLabel> groups = new ArrayList<>();
        new TreeMap<>(fields).forEach((group, f) ->
                groups.add(new GroupLabel(group, titles.getOrDefault(group, ""), List.copyOf(f.values()))));
        return new LabelFile(name, groups, redirects);
    }

    private static List<String> specs(String help) {
        List<String> out = new ArrayList<>();
        for (String part : help.split("\\\\n")) {
            String s = stripTrailingCommas(part);
            if (SPEC_LINE.matcher(s).matches()) {
                out.add(s);
            }
        }
        return out;
    }

    private static Optional<Redirect> parseRedirect(String line) {
        int comment = line.indexOf(';');
        String body = comment >= 0 ? line.substring(0, comment) : line;
        String[] cols = body.split(",");
        if (cols.length < 3 || cols[1].isBlank()) {
            return Optional.empty(); // bare REDIRECT,<file>: ignored, see the class comment
        }
        String target = cols[1].strip();
        String third = cols[2].strip();
        if (third.contains("-")) {
            boolean hardware = false;
            if (cols.length >= 4) {
                String qualifier = cols[3].strip();
                if (qualifier.equalsIgnoreCase("HW")) {
                    hardware = true;
                } else if (!qualifier.isEmpty()) {
                    return Optional.empty(); // e.g. SL: not understood, skipped
                }
            }
            return Optional.of(new Redirect(target, List.of(), Optional.of(third), hardware));
        }
        List<String> suffixes = new ArrayList<>();
        for (int i = 2; i < cols.length; i++) {
            String s = cols[i].strip().toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) {
                suffixes.add(s);
            }
        }
        if (suffixes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Redirect(target, suffixes, Optional.empty(), false));
    }

    private static String stripTrailingCommas(String s) {
        String t = s.strip();
        while (t.endsWith(",")) {
            t = t.substring(0, t.length() - 1).strip();
        }
        return t;
    }
}
