package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One reading of chosen live values from one module.
 *
 * @param module the module
 * @param status how it went
 * @param labelSource the label file the names came from; empty without labels
 * @param values one per requested ID, in request order
 * @param notes readable notes
 * @param limitHit a limit that stopped the read early, if any
 * @param requests requests sent to the car
 * @param startedAt when the read started
 * @param finishedAt when it ended
 */
public record LiveDataSnapshot(ModuleAddress module, Status status, Optional<String> labelSource,
        List<LiveValue> values, List<String> notes, Optional<LimitHit> limitHit, int requests, Instant startedAt,
        Instant finishedAt) {

    public enum Status {
        /** Every group was read. */
        OK,
        /** Some groups were read, some not (see the values' problems). */
        PARTIAL,
        /** The module did not answer. */
        NOT_RESPONDING,
        /**
         * The module reports a different part number than when its catalog was built (another car, or a module
         * swapped): nothing but its identification was read; call {@code liveDataCatalog} again.
         */
        CATALOG_CHANGED,
        /** Nothing could be read (see notes). */
        ERROR
    }

    public LiveDataSnapshot {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(labelSource, "labelSource");
        values = List.copyOf(values);
        notes = List.copyOf(notes);
        Objects.requireNonNull(limitHit, "limitHit");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
    }
}
