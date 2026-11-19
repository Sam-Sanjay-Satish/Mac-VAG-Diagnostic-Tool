package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which live values a module offers: its measuring-block groups and their fields, named from the label file when
 * there is one (field names and spec ranges only, user's decision 2026-11-19), otherwise found by asking the
 * module group by group.
 *
 * @param module the module
 * @param source where the list comes from
 * @param partNumber the part number the module reported, used to find the labels
 * @param labelSource the label file, e.g. {@code 03L-906-022-CBE.lbl}; empty without labels
 * @param labelTrail the label files consulted, in order (or why none was found)
 * @param groups the readable groups, by number
 * @param withheldGroups groups not offered for privacy (their labels say VIN or immobilizer / key identity, or a
 *        probed response looked like a VIN); numbers only
 * @param probedThrough without labels: the highest group number asked so far (255 = all); with labels 0
 * @param notes readable notes (what was refused, what failed, what to do next)
 * @param limitHit a limit that stopped this call early, if any
 * @param requests requests sent to the car for this call
 * @param checkedAt when this was built or last extended
 */
public record LiveDataCatalog(ModuleAddress module, Source source, Optional<String> partNumber,
        Optional<String> labelSource, List<String> labelTrail, List<Group> groups, List<Integer> withheldGroups,
        int probedThrough, List<String> notes, Optional<LimitHit> limitHit, int requests, Instant checkedAt) {

    /** Where the catalog came from. */
    public enum Source {
        /** From the module's label file. */
        LABELS,
        /** No label file: every group 001..255 was asked, these answered. */
        PROBED,
        /** No label file, and not every group has been asked yet: call again to continue. */
        PROBE_INCOMPLETE,
        /** The module has no measuring blocks we can read (UDS, or no diagnostic link). Nothing was sent. */
        NOT_SUPPORTED,
        /** The module did not answer. */
        NOT_RESPONDING
    }

    /**
     * One group.
     *
     * @param group 1..255
     * @param title the label's group title, empty without labels
     * @param fields the fields offered
     */
    public record Group(int group, String title, List<Field> fields) {
        public Group {
            Objects.requireNonNull(title, "title");
            fields = List.copyOf(fields);
        }
    }

    /**
     * One field.
     *
     * @param id the value ID to read it with, e.g. {@code 01:007.3}
     * @param name the label's name, e.g. {@code Coolant Temperature (G62)}; empty without labels
     * @param quantity without labels: the formula's generic name seen when probing ({@code Temperature}); else empty
     * @param unit without labels: the unit seen when probing ({@code °C}); else empty (known once read)
     * @param specs the label's range / specification lines, e.g. {@code Specification (Idle): 760...940 U/min}
     */
    public record Field(LiveValueId id, String name, String quantity, String unit, List<String> specs) {
        public Field {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(unit, "unit");
            specs = List.copyOf(specs);
        }
    }

    public LiveDataCatalog {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(partNumber, "partNumber");
        Objects.requireNonNull(labelSource, "labelSource");
        labelTrail = List.copyOf(labelTrail);
        groups = List.copyOf(groups);
        withheldGroups = List.copyOf(withheldGroups);
        notes = List.copyOf(notes);
        Objects.requireNonNull(limitHit, "limitHit");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    /** The field with this ID, if the catalog offers it. */
    public Optional<Field> field(LiveValueId id) {
        if (!id.module().equals(module)) {
            return Optional.empty();
        }
        return groups.stream().filter(g -> g.group() == id.group()).flatMap(g -> g.fields().stream())
                .filter(f -> f.id().equals(id)).findFirst();
    }

    public int fieldCount() {
        return groups.stream().mapToInt(g -> g.fields().size()).sum();
    }
}
