package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether one known module answered. Without the gateway's installation list (open question) "not responding"
 * can mean "not installed" or "not reachable".
 *
 * @param module the module
 * @param reachability the outcome
 * @param link the link it answered on, if it did
 * @param message readable detail
 * @param checkedAt when the car was last asked (may be up to {@link ScanLimits#moduleListCacheTtl()} ago)
 */
public record ModuleAvailability(ModuleAddress module, Reachability reachability, Optional<ModuleLink> link,
        String message, Instant checkedAt) {

    public enum Reachability {
        RESPONDING,
        /** No answer: not installed, or not reachable (ignition off, wiring). */
        NOT_RESPONDING,
        /** Not asked: a limit stopped the check; see {@link #message()}. */
        NOT_CHECKED
    }

    public ModuleAvailability {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(reachability, "reachability");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
