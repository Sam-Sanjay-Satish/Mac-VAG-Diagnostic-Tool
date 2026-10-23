package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.TimeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What scan remembers about modules between calls: which link answered last (tried first next time), which
 * modules didn't respond recently (back-off), and the last module list for {@link ScanService#listModules}.
 */
final class ModuleHealth {

    private record NotResponding(long sinceNanos, String why) {
    }

    private final TimeSource time;
    private final ScanLimits limits;
    private final Map<ModuleAddress, ModuleLink> workingLinks = new HashMap<>();
    private final Map<ModuleAddress, NotResponding> notResponding = new HashMap<>();
    private List<ModuleAvailability> listing;
    private long listingAtNanos;

    ModuleHealth(TimeSource time, ScanLimits limits) {
        this.time = time;
        this.limits = limits;
    }

    /** The module's links, the one that answered last first. */
    synchronized List<ModuleLink> linkOrder(ModuleAddress module) {
        List<ModuleLink> order = new ArrayList<>(module.links());
        ModuleLink working = workingLinks.get(module);
        if (working != null && order.remove(working)) {
            order.addFirst(working);
        }
        return order;
    }

    synchronized void markResponding(ModuleAddress module, ModuleLink link) {
        workingLinks.put(module, link);
        notResponding.remove(module);
    }

    synchronized void markNotResponding(ModuleAddress module, String why) {
        notResponding.put(module, new NotResponding(time.nanoTime(), why));
    }

    /** How long the module is still in back-off, if it is. */
    synchronized Optional<Duration> backoffRemaining(ModuleAddress module) {
        NotResponding n = notResponding.get(module);
        if (n == null) {
            return Optional.empty();
        }
        long left = n.sinceNanos + limits.notRespondingBackoff().toNanos() - time.nanoTime();
        if (left <= 0) {
            notResponding.remove(module);
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(left));
    }

    synchronized String lastFailure(ModuleAddress module) {
        NotResponding n = notResponding.get(module);
        return n == null ? "no response" : n.why;
    }

    synchronized void cacheListing(List<ModuleAvailability> list) {
        listing = List.copyOf(list);
        listingAtNanos = time.nanoTime();
    }

    synchronized Optional<List<ModuleAvailability>> cachedListing() {
        if (listing == null || time.nanoTime() - listingAtNanos >= limits.moduleListCacheTtl().toNanos()) {
            return Optional.empty();
        }
        return Optional.of(listing);
    }
}
