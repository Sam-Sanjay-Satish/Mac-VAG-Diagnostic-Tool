package io.github.samsanjaysatish.macvagdiag.diag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * A KWP2000 measuring-value triplet {@code [formula] [a] [b]}, as the engine stores them in its freeze frames.
 *
 * <p>Every formula here was checked against the 2026-11-13 VCDS log of the same records (all 84 environment
 * values of the 12 engine freeze frames): same number, same precision, same unit. VCDS's labels are plain
 * quantity names; they are repeated so the output reads like the VCDS report. Unknown formulas are returned raw
 * by {@link #describe}.
 */
final class KwpMeasuringValue {

    private KwpMeasuringValue() {
    }

    /** value = numerator / denominator, rounded half up to {@code decimals}. */
    private interface Formula {
        Optional<BigDecimal> value(int a, int b);
    }

    private record Kind(String label, String unit, int decimals, Formula formula) {
    }

    private static Formula ratio(java.util.function.IntBinaryOperator numerator, int denominator) {
        return (a, b) -> Optional.of(BigDecimal.valueOf(numerator.applyAsInt(a, b))
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP));
    }

    /** Formula byte → how to show it. Unit strings are as VCDS prints them, including the leading space or not. */
    private static final Map<Integer, Kind> KINDS = Map.ofEntries(
            Map.entry(0x01, new Kind("RPM", " /min", 0, ratio((a, b) -> 2 * a * b, 10))),           // 73 24 -> 828
            Map.entry(0x05, new Kind("Temperature", "°C", 1, ratio((a, b) -> a * (b - 100), 10))),  // 09 73 -> 13.5
            Map.entry(0x06, new Kind("Voltage", " V", 2, ratio((a, b) -> a * b, 1000))),            // 4C B6 -> 13.83
            Map.entry(0x07, new Kind("Speed", " km/h", 1, ratio((a, b) -> a * b, 100))),            // 64 4B -> 75.0
            Map.entry(0x08, new Kind("(no units)", "", 1, ratio((a, b) -> a * b, 10))),             // 0A 05 -> 5.0
            Map.entry(0x13, new Kind("Tank Cont.", " l", 1, ratio((a, b) -> a * b, 100))),          // 1C 0A -> 2.8
            Map.entry(0x14, new Kind("Lambda", " %", 1, ratio((a, b) -> a * (b - 128), 128))),      // 65 AC -> 34.7
            Map.entry(0x21, new Kind("Load", " %", 1, (a, b) -> a == 0 ? Optional.empty()           // FF B7 -> 71.8
                    : Optional.of(BigDecimal.valueOf(100L * b).divide(BigDecimal.valueOf(a), 6, RoundingMode.HALF_UP)))),
            Map.entry(0x27, new Kind("Inj. Quantity", " mg/str", 1, ratio((a, b) -> a * b, 255))),  // 46 9C -> 42.8
            Map.entry(0x31, new Kind("Mass Air / Rev.", " mg/str", 1, ratio((a, b) -> a * b, 40))), // C8 34 -> 260.0
            Map.entry(0x60, new Kind("Pressure", " mbar", 1, ratio((a, b) -> a * b, 10))),          // 79 53 -> 1004.3
            Map.entry(0x64, new Kind("Pressure", " bar", 1, ratio((a, b) -> a * b, 10))));          // 50 AB -> 1368.0

    /** True if {@link #describe} decodes this formula byte. */
    static boolean known(int formula) {
        return KINDS.containsKey(formula);
    }

    /**
     * As VCDS prints it, e.g. {@code RPM: 828 /min}, {@code Temperature: 13.5°C}; an unknown formula (or one whose
     * value can't be computed, like {@code 21} with {@code a = 0}) as {@code formula 99: 12 34 (not decoded)}.
     */
    static String describe(int formula, int a, int b) {
        Kind k = KINDS.get(formula);
        Optional<BigDecimal> v = k == null ? Optional.empty() : k.formula().value(a, b);
        if (v.isEmpty()) {
            return String.format("formula %02X: %02X %02X (not decoded)", formula, a, b);
        }
        return k.label() + ": " + v.get().setScale(k.decimals(), RoundingMode.HALF_UP).toPlainString() + k.unit();
    }
}
