package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A whole Auto-Scan: vehicle info, then one {@link ModuleResult} per known module, in address order.
 *
 * @param scanId the scan's ID, e.g. {@code scan-3}
 * @param caller who started it
 * @param startedAt start time
 * @param finishedAt end time
 * @param vin the VIN (unmasked), from the first module that reported one
 * @param vinSource which module it came from
 * @param modules every known module's result
 * @param cancelled true if it was cancelled before the end
 * @param demo true if this came from the simulated demo car
 * @param notes readable notes about the scan as a whole
 */
public record AutoScanResult(String scanId, Caller caller, Instant startedAt, Instant finishedAt, Optional<String> vin,
        Optional<ModuleAddress> vinSource, List<ModuleResult> modules, boolean cancelled, boolean demo,
        List<String> notes) {

    public AutoScanResult {
        Objects.requireNonNull(scanId, "scanId");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(vin, "vin");
        Objects.requireNonNull(vinSource, "vinSource");
        modules = List.copyOf(modules);
        notes = List.copyOf(notes);
    }

    /** Modules that answered. */
    public List<ModuleResult> respondingModules() {
        return modules.stream().filter(ModuleResult::responded).toList();
    }

    /** All fault codes across modules. */
    public int faultCount() {
        return modules.stream().mapToInt(m -> m.faults().size()).sum();
    }

    /** The result for {@code module}. */
    public Optional<ModuleResult> module(ModuleAddress module) {
        return modules.stream().filter(m -> m.module().equals(module)).findFirst();
    }

    /** A copy with the VIN masked everywhere, including raw identification records. */
    public AutoScanResult maskVin() {
        return VinMask.mask(this);
    }
}
