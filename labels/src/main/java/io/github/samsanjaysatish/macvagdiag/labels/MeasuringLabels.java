package io.github.samsanjaysatish.macvagdiag.labels;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The measuring-block labels of one module, from one label file.
 *
 * <p>Groups whose title or field names say they hold the VIN or immobilizer identity are <b>withheld</b>
 * ({@link PrivacyFilter}, user's decision 2026-11-19): they are not in {@link #groups()}, so nothing above can
 * offer or read them, and only their numbers are kept, in {@link #withheldGroups()}.
 *
 * @param source the label file the labels came from, e.g. {@code 03L-906-022-CBE.lbl}
 * @param groups the readable groups by number
 * @param withheldGroups groups withheld for privacy (numbers only)
 */
public record MeasuringLabels(String source, SortedMap<Integer, GroupLabel> groups, SortedSet<Integer> withheldGroups) {

    public MeasuringLabels {
        Objects.requireNonNull(source, "source");
        groups = Collections.unmodifiableSortedMap(new TreeMap<>(groups));
        withheldGroups = Collections.unmodifiableSortedSet(new TreeSet<>(withheldGroups));
        for (Integer g : withheldGroups) {
            if (groups.containsKey(g)) {
                throw new IllegalArgumentException("group " + g + " is both readable and withheld");
            }
        }
    }

    /** Splits parsed groups into readable and withheld. */
    static MeasuringLabels of(String source, List<GroupLabel> parsed) {
        SortedMap<Integer, GroupLabel> readable = new TreeMap<>();
        SortedSet<Integer> withheld = new TreeSet<>();
        for (GroupLabel g : parsed) {
            if (PrivacyFilter.withholds(g)) {
                withheld.add(g.group());
            } else {
                readable.put(g.group(), g);
            }
        }
        return new MeasuringLabels(source, readable, withheld);
    }

    public Optional<GroupLabel> group(int group) {
        return Optional.ofNullable(groups.get(group));
    }
}
