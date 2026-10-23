package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A UDS DTC status byte (ISO 14229-1 statusOfDTC). All eight bits are defined by the standard. The bit layout is
 * from memory of ISO 14229-1 (UNCONFIRMED against the capture), and how VCDS words each combination is for the
 * VCDS log to tell.
 *
 * @param raw the byte as received
 */
public record UdsDtcStatus(int raw) implements DtcStatus {

    /** The status bits, ISO 14229-1 names. */
    public enum Flag {
        TEST_FAILED(0x01, "testFailed"),
        TEST_FAILED_THIS_OPERATION_CYCLE(0x02, "testFailedThisOperationCycle"),
        PENDING_DTC(0x04, "pendingDTC"),
        CONFIRMED_DTC(0x08, "confirmedDTC"),
        TEST_NOT_COMPLETED_SINCE_LAST_CLEAR(0x10, "testNotCompletedSinceLastClear"),
        TEST_FAILED_SINCE_LAST_CLEAR(0x20, "testFailedSinceLastClear"),
        TEST_NOT_COMPLETED_THIS_OPERATION_CYCLE(0x40, "testNotCompletedThisOperationCycle"),
        WARNING_INDICATOR_REQUESTED(0x80, "warningIndicatorRequested");

        private final int mask;
        private final String isoName;

        Flag(int mask, String isoName) {
            this.mask = mask;
            this.isoName = isoName;
        }

        public int mask() {
            return mask;
        }

        public String isoName() {
            return isoName;
        }
    }

    public UdsDtcStatus {
        if (raw < 0 || raw > 0xFF) {
            throw new IllegalArgumentException("Status must be a byte: " + raw);
        }
    }

    public boolean has(Flag flag) {
        return (raw & flag.mask()) != 0;
    }

    /** The flags that are set. */
    public Set<Flag> flags() {
        EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
        for (Flag f : Flag.values()) {
            if (has(f)) {
                set.add(f);
            }
        }
        return set;
    }

    @Override
    public List<String> flagNames() {
        List<String> names = new ArrayList<>();
        for (Flag f : flags()) {
            names.add(f.isoName());
        }
        return List.copyOf(names);
    }

    @Override
    public String toString() {
        return String.format("%02X %s", raw, flagNames());
    }
}
