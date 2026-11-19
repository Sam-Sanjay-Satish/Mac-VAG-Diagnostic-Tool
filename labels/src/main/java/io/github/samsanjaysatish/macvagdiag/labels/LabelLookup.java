package io.github.samsanjaysatish.macvagdiag.labels;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of looking up one module's labels. "No label file" is a normal result: the values are then shown
 * with their raw IDs and the formula's generic quantity and unit.
 *
 * @param labels the measuring-block labels, empty if no file was found
 * @param trail the files consulted, in order, e.g. {@code [03-01.lbl, 03L-906-022-CBE.lbl]}, and notes such as a
 *        redirect whose target is missing
 */
public record LabelLookup(Optional<MeasuringLabels> labels, List<String> trail) {

    public LabelLookup {
        Objects.requireNonNull(labels, "labels");
        trail = List.copyOf(trail);
    }

    public static LabelLookup none(List<String> trail) {
        return new LabelLookup(Optional.empty(), trail);
    }

    /** The file the labels came from, e.g. {@code 03L-906-022-CBE.lbl}; empty if none. */
    public Optional<String> source() {
        return labels.map(MeasuringLabels::source);
    }
}
