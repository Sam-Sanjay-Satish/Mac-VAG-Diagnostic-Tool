package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A whole Auto-Scan: vehicle info, the gateway's installation list, then one {@link ModuleResult} per module
 * scanned, in address order. With the installation list, those are the modules it lists as installed (as in
 * VCDS); without it (gateway not reachable), every known module with a link.
 *
 * @param scanId the scan's ID, e.g. {@code scan-3}
 * @param caller who started it
 * @param startedAt start time
 * @param finishedAt end time
 * @param vin the VIN (unmasked), from the first module that reported one
 * @param vinSource which module it came from
 * @param modules the result of each module scanned
 * @param cancelled true if it was cancelled before the end
 * @param demo true if this came from the simulated demo car
 * @param notes readable notes about the scan as a whole
 * @param installationList the gateway's status of every installed module, in the gateway's order (including
 *     modules not in our table); empty if the list wasn't read and the fixed list was probed instead
 */
public record AutoScanResult(String scanId, Caller caller, Instant startedAt, Instant finishedAt, Optional<String> vin,
        Optional<ModuleAddress> vinSource, List<ModuleResult> modules, boolean cancelled, boolean demo,
        List<String> notes, List<GatewayStatus> installationList) {

    /** Without an installation list (the fixed list was probed). */
    public AutoScanResult(String scanId, Caller caller, Instant startedAt, Instant finishedAt, Optional<String> vin,
            Optional<ModuleAddress> vinSource, List<ModuleResult> modules, boolean cancelled, boolean demo,
            List<String> notes) {
        this(scanId, caller, startedAt, finishedAt, vin, vinSource, modules, cancelled, demo, notes, List.of());
    }

    public AutoScanResult {
        Objects.requireNonNull(scanId, "scanId");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(vin, "vin");
        Objects.requireNonNull(vinSource, "vinSource");
        modules = List.copyOf(modules);
        notes = List.copyOf(notes);
        installationList = List.copyOf(installationList);
    }

    /** True if the modules came from the gateway's installation list (not the fixed list). */
    public boolean fromInstallationList() {
        return !installationList.isEmpty();
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
