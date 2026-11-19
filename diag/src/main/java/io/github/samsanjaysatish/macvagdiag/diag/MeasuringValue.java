package io.github.samsanjaysatish.macvagdiag.diag;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One field of a KWP2000 measuring block: the triplet {@code [formula] [a] [b]} as received, and what it decodes
 * to. The unit comes from the formula byte the module sends, so it is known without a label file; what the value
 * <i>means</i> (coolant vs intake air temperature) is not, and is added above this layer from the label files.
 *
 * @param field VCDS's field number, 1..4 (the first four triplets of the response)
 * @param formula the formula byte
 * @param a first value byte
 * @param b second value byte
 * @param quantity the formula's generic name, as VCDS shows it without labels ({@code Temperature}, {@code RPM});
 *        empty for an unknown formula or an empty field
 * @param unit the unit ({@code °C}, {@code /min}, {@code mbar}); empty if none or unknown
 * @param value the decoded number at VCDS's precision; empty if the field is empty, the formula unknown, or the
 *        value can't be computed (e.g. formula {@code 21} with {@code a = 0})
 */
public record MeasuringValue(int field, int formula, int a, int b, String quantity, String unit,
        Optional<BigDecimal> value) {

    public MeasuringValue {
        if (field < 1) {
            throw new IllegalArgumentException("field must be 1 or more: " + field);
        }
        checkByte(formula, "formula");
        checkByte(a, "a");
        checkByte(b, "b");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(value, "value");
    }

    /** Decodes one triplet with the KWP formulas ({@code KwpMeasuringValue}). */
    static MeasuringValue of(int field, int formula, int a, int b) {
        if (KwpMeasuringValue.isEmpty(formula, a, b)) {
            return new MeasuringValue(field, formula, a, b, "", "", Optional.empty());
        }
        KwpMeasuringValue.Decoded d = KwpMeasuringValue.decode(formula, a, b);
        return new MeasuringValue(field, formula, a, b, d.quantity(), d.unit(), d.value());
    }

    /** True if the module sent "no value" for this field ({@code 25 00 00}); VCDS shows a blank cell. */
    public boolean empty() {
        return KwpMeasuringValue.isEmpty(formula, a, b);
    }

    /** True if {@link #value} holds a number. */
    public boolean decoded() {
        return value.isPresent();
    }

    /** The triplet as on the bus, e.g. {@code 05 09 8F}. */
    public String raw() {
        return String.format("%02X %02X %02X", formula, a, b);
    }

    /**
     * Short text: {@code 38.7 °C}, {@code 828 /min}, {@code (empty)}, or {@code formula 1F: 50 20 (not decoded)}.
     */
    public String text() {
        if (empty()) {
            return "(empty)";
        }
        if (value.isEmpty()) {
            return String.format("formula %02X: %02X %02X (not decoded)", formula, a, b);
        }
        String number = value.get().toPlainString();
        return unit.isEmpty() ? number : number + " " + unit;
    }

    private static void checkByte(int v, String what) {
        if (v < 0 || v > 0xFF) {
            throw new IllegalArgumentException(what + " must be a byte: " + v);
        }
    }
}
