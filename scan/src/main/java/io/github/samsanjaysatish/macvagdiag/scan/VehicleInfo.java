package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The identification of the main modules ({@code 01}, {@code 17}, {@code 19}) and the mileage (from {@code 17}).
 * The VIN is never read (user's decision, 2026-11-17).
 *
 * @param mileage the odometer, from 17 Instruments, if it was read
 * @param modules the identification of each module asked
 * @param readAt when it was read
 * @param demo true if this came from the simulated demo car
 */
public record VehicleInfo(Optional<Mileage> mileage, List<ModuleIdentity> modules, Instant readAt, boolean demo) {

    public VehicleInfo {
        Objects.requireNonNull(mileage, "mileage");
        modules = List.copyOf(modules);
        Objects.requireNonNull(readAt, "readAt");
    }
}
