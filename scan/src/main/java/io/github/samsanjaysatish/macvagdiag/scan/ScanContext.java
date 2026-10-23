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

    ScanContext(String id, Caller caller, String operation, BooleanSupplier cancelled) {
        this.id = Objects.requireNonNull(id, "id");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
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
