package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.Objects;

/**
 * Is the tool ready to talk to the car?
 *
 * @param adapter whether the CAN adapter is open and usable
 * @param adapterDetail readable detail, e.g. the open error
 * @param ignition always {@link VehicleCondition.State#UNKNOWN} for now (see {@link VehicleCondition})
 * @param engine always {@link VehicleCondition.State#UNKNOWN} for now
 * @param demo true when the "car" is the built-in simulated demo car
 * @param checkedAt when this was checked
 */
public record SessionStatus(AdapterState adapter, String adapterDetail, VehicleCondition ignition,
        VehicleCondition engine, boolean demo, Instant checkedAt) {

    public enum AdapterState { CONNECTED, DISCONNECTED }

    /**
     * Ignition or engine state. Telling them needs requests that aren't on the approved allowlist (e.g. engine RPM
     * from a measuring block), so both stay {@link State#UNKNOWN} with a reason until the user decides (open
     * question in {@code scan/CLAUDE.md}).
     */
    public record VehicleCondition(State state, String reason) {

        public enum State { ON, OFF, UNKNOWN }

        public VehicleCondition {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }

        public static VehicleCondition unknown(String reason) {
            return new VehicleCondition(State.UNKNOWN, reason);
        }
    }

    public SessionStatus {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(adapterDetail, "adapterDetail");
        Objects.requireNonNull(ignition, "ignition");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public boolean adapterConnected() {
        return adapter == AdapterState.CONNECTED;
    }
}
