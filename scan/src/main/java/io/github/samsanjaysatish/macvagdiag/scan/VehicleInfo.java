package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The VIN and the identification of the modules read for it ({@code 01}, {@code 17}, {@code 19}; UNCONFIRMED
 * which of them hold the VIN on this car). The VIN is unmasked; use {@link #maskVin()} for display.
 *
 * @param vin the VIN, from the first module that reported one
 * @param vinSource which module it came from
 * @param modules the identification of each module asked
 * @param readAt when it was read
 * @param demo true if this came from the simulated demo car
 */
public record VehicleInfo(Optional<String> vin, Optional<ModuleAddress> vinSource, List<ModuleIdentity> modules,
        Instant readAt, boolean demo) {

    public VehicleInfo {
        Objects.requireNonNull(vin, "vin");
        Objects.requireNonNull(vinSource, "vinSource");
        modules = List.copyOf(modules);
        Objects.requireNonNull(readAt, "readAt");
    }

    /** A copy with the VIN masked everywhere, including raw identification records. */
    public VehicleInfo maskVin() {
        return VinMask.mask(this);
    }
}
