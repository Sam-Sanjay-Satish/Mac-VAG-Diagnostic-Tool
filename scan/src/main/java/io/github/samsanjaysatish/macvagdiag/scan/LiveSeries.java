package io.github.samsanjaysatish.macvagdiag.scan;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The summary of one value over a recording: counts, min / max / average of the decoded samples, and notable
 * events (samples outside the label's {@code Range:}, gaps).
 *
 * @param id e.g. {@code 01:011.3}
 * @param name the label's name, empty without labels
 * @param quantity the formula's generic name, empty if unknown
 * @param unit e.g. {@code mbar}
 * @param specs the label's range / specification lines
 * @param samples how many times its group was read
 * @param decoded how many of those gave a number
 * @param min smallest decoded value
 * @param max largest decoded value
 * @param average mean of the decoded values, one decimal more than the values
 * @param first first decoded value
 * @param last last decoded value
 * @param minAtSeconds seconds from the start when the minimum was first seen
 * @param maxAtSeconds seconds from the start when the maximum was first seen
 * @param events readable notable events, e.g. {@code 3 of 40 samples outside Range: 0.0...2600.0 mbar}
 */
public record LiveSeries(LiveValueId id, String name, String quantity, String unit, List<String> specs,
        int samples, int decoded, Optional<BigDecimal> min, Optional<BigDecimal> max, Optional<BigDecimal> average,
        Optional<BigDecimal> first, Optional<BigDecimal> last, Optional<Double> minAtSeconds,
        Optional<Double> maxAtSeconds, List<String> events) {

    public LiveSeries {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        specs = List.copyOf(specs);
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(average, "average");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(minAtSeconds, "minAtSeconds");
        Objects.requireNonNull(maxAtSeconds, "maxAtSeconds");
        events = List.copyOf(events);
    }
}
