package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * One top-level call ("a scan" for the budgets): its ID, caller, per-module request counts and cancel flag.
 * Request counts are only touched on the worker thread.
 */
final class ScanContext {

    final String id;
    final Caller caller;
    final String operation;
    private final BooleanSupplier cancelled;
    private final Map<ModuleAddress, Integer> requests = new HashMap<>();
    /** A per-module budget for this call instead of {@link ScanLimits#maxRequestsPerModulePerScan()}; 0 = none. */
    private volatile int moduleBudget;

    ScanContext(String id, Caller caller, String operation, BooleanSupplier cancelled) {
        this.id = Objects.requireNonNull(id, "id");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    /**
     * Gives this call its own per-module budget (live-data recordings, whose request count follows the duration,
     * bounded by {@code LiveDataEngine}). The one-minute window still applies.
     */
    void moduleBudget(int budget) {
        if (budget < 1) {
            throw new IllegalArgumentException("budget must be positive: " + budget);
        }
        moduleBudget = budget;
    }

    /** The per-module budget for this call. */
    int moduleBudget(ScanLimits limits) {
        int b = moduleBudget;
        return b > 0 ? b : limits.maxRequestsPerModulePerScan();
    }

    boolean cancelled() {
        return cancelled.getAsBoolean();
    }

    synchronized int requests(ModuleAddress module) {
        return requests.getOrDefault(module, 0);
    }

    synchronized void charge(ModuleAddress module, int count) {
        requests.merge(module, count, Integer::sum);
    }
}
