package io.github.samsanjaysatish.macvagdiag.scan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One live value as read.
 *
 * @param id e.g. {@code 01:007.3}
 * @param name the label's name, empty without labels
 * @param quantity the formula's generic name ({@code Temperature}), empty if unknown
 * @param unit e.g. {@code °C}; empty if none or unknown
 * @param value the number, at VCDS's precision; empty unless {@code state} is {@link State#OK}
 * @param text short readable form, e.g. {@code 87.3 °C}, {@code (empty)}, {@code formula 1F: 50 20 (not decoded)}
 * @param raw the triplet as received, e.g. {@code 05 09 8F}; empty if not read
 * @param specs the label's range / specification lines
 * @param state what happened
 * @param problem why it isn't {@link State#OK}, readable; empty when OK
 * @param readAt when the group was read (or tried)
 */
public record LiveValue(LiveValueId id, String name, String quantity, String unit, Optional<BigDecimal> value,
        String text, String raw, List<String> specs, State state, Optional<String> problem, Instant readAt) {

    public enum State {
        /** Decoded to a number with its unit. */
        OK,
        /** The module sent "no value" ({@code 25 00 00}); VCDS shows a blank cell. */
        EMPTY,
        /** Read, but the formula isn't decoded yet: see {@code raw}. */
        NOT_DECODED,
        /** Not read: the group failed, was refused, or the module didn't send this field. */
        NOT_READ,
        /** The group's response looked like it contained a VIN, so it was dropped. */
        WITHHELD
    }

    public LiveValue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(raw, "raw");
        specs = List.copyOf(specs);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(readAt, "readAt");
    }
}
