package io.github.samsanjaysatish.macvagdiag.labels;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The label of one measuring-block group: its title ("Temperatures") and the labels of fields 1..4 that the file
 * names (a field the file doesn't list is absent).
 *
 * @param group 1..255
 * @param title the group's title (field 0 in the file), may be empty
 * @param fields the labelled fields, in field order
 */
public record GroupLabel(int group, String title, List<FieldLabel> fields) {

    /** VCDS shows four fields per group. */
    public static final int FIELDS = 4;

    public GroupLabel {
        if (group < 1 || group > 0xFF) {
            throw new IllegalArgumentException("group must be 1..255: " + group);
        }
        Objects.requireNonNull(title, "title");
        fields = List.copyOf(fields);
    }

    public Optional<FieldLabel> field(int n) {
        return fields.stream().filter(f -> f.field() == n).findFirst();
    }
}
