package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The fault codes a module reported, plus everything needed to show what wasn't decoded.
 *
 * @param dtcs the decoded DTCs, in the order received (may be empty)
 * @param response the whole positive response as received
 * @param undecoded bytes left over after the last whole DTC record (truncated response); empty normally
 * @param statusAvailabilityMask UDS only: the module's DTC status availability mask ({@code 59 02 [mask]})
 * @param warnings readable notes about anything that didn't add up (count mismatch, truncation); empty normally
 */
public record DtcList(List<Dtc> dtcs, RawBytes response, RawBytes undecoded, OptionalInt statusAvailabilityMask,
        List<String> warnings) {

    public DtcList {
        dtcs = List.copyOf(dtcs);
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(undecoded, "undecoded");
        Objects.requireNonNull(statusAvailabilityMask, "statusAvailabilityMask");
        warnings = List.copyOf(warnings);
    }

    /** True if the response decoded cleanly: no warnings and no left-over bytes. */
    public boolean complete() {
        return warnings.isEmpty() && undecoded.isEmpty();
    }
}
