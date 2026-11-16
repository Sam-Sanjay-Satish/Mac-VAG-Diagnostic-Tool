package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether one known module answered. When the gateway's installation list was read, modules it doesn't list are
 * {@link Reachability#NOT_INSTALLED} (not contacted) and {@link #gatewayStatus()} carries the gateway's view of the
 * others; without the list, "not responding" can mean "not installed" or "not reachable".
 *
 * @param module the module
 * @param reachability the outcome
 * @param link the link it answered on, if it did
 * @param message readable detail
 * @param checkedAt when the car was last asked (may be up to {@link ScanLimits#moduleListCacheTtl()} ago)
 * @param gatewayStatus what the gateway's installation list said about it, if the list was read
 */
public record ModuleAvailability(ModuleAddress module, Reachability reachability, Optional<ModuleLink> link,
        String message, Instant checkedAt, Optional<GatewayStatus> gatewayStatus) {

    /** Without a gateway status. */
    public ModuleAvailability(ModuleAddress module, Reachability reachability, Optional<ModuleLink> link,
            String message, Instant checkedAt) {
        this(module, reachability, link, message, checkedAt, Optional.empty());
    }

    public enum Reachability {
        RESPONDING,
        /** No answer: not installed, or not reachable (ignition off, wiring). */
        NOT_RESPONDING,
        /**
         * Not asked: a limit stopped the check, or the module has no link of its own (04: see
         * {@link #gatewayStatus()}); see {@link #message()}.
         */
        NOT_CHECKED,
        /** The gateway's installation list doesn't list it: not contacted. */
        NOT_INSTALLED
    }

    public ModuleAvailability {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(reachability, "reachability");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(gatewayStatus, "gatewayStatus");
    }

    /** A copy with {@code status} as the gateway status. */
    public ModuleAvailability withGatewayStatus(GatewayStatus status) {
        return new ModuleAvailability(module, reachability, link, message, checkedAt, Optional.of(status));
    }
}
