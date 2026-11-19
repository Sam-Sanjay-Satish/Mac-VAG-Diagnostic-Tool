package io.github.samsanjaysatish.macvagdiag.labels;

import java.util.List;
import java.util.Objects;

/**
 * The label of one measuring-block field, e.g. group 001 field 4 of the target car's engine:
 * {@code name} "Coolant", {@code detail} "Temperature (G62)", specs "Range: -90.0...+139.5 °C",
 * "Specification (Warm): 80.0...110.0 °C".
 *
 * <p>Only the name, the detail and the range / specification lines are kept from the label file (user's
 * decision 2026-11-19: names and specs go to the AI agent, nothing else). Other help text (bit tables, notes) is
 * dropped while parsing.
 *
 * @param field 1..4
 * @param name the label's name column, may be empty
 * @param detail the second column (component code, "(actual)", ...), may be empty
 * @param specs the help lines that start with {@code Range} or {@code Specification}, in file order
 */
public record FieldLabel(int field, String name, String detail, List<String> specs) {

    public FieldLabel {
        if (field < 1 || field > GroupLabel.FIELDS) {
            throw new IllegalArgumentException("field must be 1..4: " + field);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(detail, "detail");
        specs = List.copyOf(specs);
    }

    /** {@code Coolant Temperature (G62)}: name and detail joined; empty if both are. */
    public String displayName() {
        if (detail.isEmpty()) {
            return name;
        }
        return name.isEmpty() ? detail : name + " " + detail;
    }
}
