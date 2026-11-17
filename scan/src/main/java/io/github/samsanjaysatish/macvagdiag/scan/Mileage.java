package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.Objects;

/**
 * The car's mileage: the odometer of 17 Instruments, read with {@code 22 22 03} as VCDS does for its report header
 * (2026-11-13 capture: {@code 62 22 03 8F 14} = 366280 km, VCDS "Mileage: 366280km-227595mi").
 *
 * @param kilometres the odometer reading in km (the cluster counts in 10 km steps; UNCONFIRMED, one sample)
 * @param source the module it came from
 * @param raw the whole response, as received
 * @param readAt when it was read
 */
public record Mileage(int kilometres, ModuleAddress source, RawValue raw, Instant readAt) {

    /** Kilometres per statute mile. */
    static final double KM_PER_MILE = 1.609344;

    public Mileage {
        if (kilometres < 0) {
            throw new IllegalArgumentException("Negative mileage: " + kilometres);
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(readAt, "readAt");
    }

    /**
     * The reading in whole miles, rounded down as VCDS does (366280 km = 227595.6 mi, which VCDS prints as
     * {@code 227595mi}).
     */
    public long miles() {
        return (long) Math.floor(kilometres / KM_PER_MILE);
    }
}
