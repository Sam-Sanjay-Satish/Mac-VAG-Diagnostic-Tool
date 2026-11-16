package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A UDS DTC status byte (ISO 14229-1 statusOfDTC). All eight bits are defined by the standard; {@link #flags()}
 * gives their ISO names. {@link #vcdsText()} words the status the way VCDS does, as far as the 2026-11-13 VCDS
 * log shows it (statuses {@code 08} and {@code 09} only).
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

    /** VCDS's words: bit 0 (testFailed) clear. */
    public static final String VCDS_INTERMITTENT = "Intermittent";
    /** VCDS's words: bit 3 (confirmedDTC) set. */
    public static final String VCDS_CONFIRMED = "Confirmed";
    /** VCDS's words: bit 4 (testNotCompletedSinceLastClear) clear. */
    public static final String VCDS_TESTED_SINCE_MEMORY_CLEAR = "Tested Since Memory Clear";

    /**
     * The words VCDS prints for this status, in its order. From the 2026-11-13 VCDS log: {@code 08} =
     * "Intermittent - Confirmed - Tested Since Memory Clear", {@code 09} = "Confirmed - Tested Since Memory
     * Clear". UNCONFIRMED: what VCDS prints for the other bits (pending, warning lamp, ...); they are not worded
     * here, {@link #flags()} still has them.
     */
    public List<String> vcdsWords() {
        List<String> words = new ArrayList<>(3);
        if (!has(Flag.TEST_FAILED)) {
            words.add(VCDS_INTERMITTENT);
        }
        if (has(Flag.CONFIRMED_DTC)) {
            words.add(VCDS_CONFIRMED);
        }
        if (!has(Flag.TEST_NOT_COMPLETED_SINCE_LAST_CLEAR)) {
            words.add(VCDS_TESTED_SINCE_MEMORY_CLEAR);
        }
        return List.copyOf(words);
    }

    /** {@link #vcdsWords()} joined as VCDS prints them, e.g. {@code Confirmed - Tested Since Memory Clear}. */
    public String vcdsText() {
        return String.join(" - ", vcdsWords());
    }

    @Override
    public String toString() {
        return String.format("%02X %s", raw, flagNames());
    }
}
