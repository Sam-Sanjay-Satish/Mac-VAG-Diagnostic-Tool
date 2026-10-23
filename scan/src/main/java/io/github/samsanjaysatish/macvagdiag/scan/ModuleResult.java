package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything read from one module.
 *
 * @param module the module
 * @param status the outcome
 * @param link the link it answered on
 * @param identification part/software numbers etc., if read (Auto-Scan reads them; {@code readFaultCodes} doesn't)
 * @param faults the stored fault codes, in the order the module sent them
 * @param faultListComplete true if the fault list decoded cleanly (no truncated or left-over bytes)
 * @param notes readable notes: failures, warnings, skipped parts
 * @param limitHit the limit that stopped part of this module, if any
 * @param requests diagnostic requests charged to this module in this scan (budget)
 * @param startedAt when work on this module started
 * @param finishedAt when it ended
 */
public record ModuleResult(ModuleAddress module, ModuleStatus status, Optional<ModuleLink> link,
        Optional<EcuInfo> identification, List<FaultCode> faults, boolean faultListComplete, List<String> notes,
        Optional<LimitHit> limitHit, int requests, Instant startedAt, Instant finishedAt) {

    public ModuleResult {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(identification, "identification");
        faults = List.copyOf(faults);
        notes = List.copyOf(notes);
        Objects.requireNonNull(limitHit, "limitHit");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
    }

    /** True if the module answered at all. */
    public boolean responded() {
        return link.isPresent() && status != ModuleStatus.NOT_RESPONDING && status != ModuleStatus.SKIPPED;
    }

    /** A copy with every VIN this module reported masked (see {@link VinMask}). */
    public ModuleResult maskVin() {
        return VinMask.mask(this, VinMask.vinsOf(List.of(this)));
    }
}
