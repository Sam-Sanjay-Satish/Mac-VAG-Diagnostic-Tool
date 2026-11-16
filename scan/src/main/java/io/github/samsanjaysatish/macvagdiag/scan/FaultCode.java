package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One stored fault code.
 *
 * @param code as shown to people: VAG decimal for KWP2000 ({@code 01314}; 6 digits when there is a P-code, e.g.
 *     {@code 011174}, as VCDS shows it), SAE J2012 for UDS (e.g. {@code B103E})
 * @param protocol the protocol it was read with
 * @param record the whole record as received (code bytes + status byte), bus-style hex
 * @param status the status byte, 0..255
 * @param statusText readable status, e.g. {@code 22 (00100010): type 002, Intermittent} (KWP2000, VCDS's words)
 * @param statusFlags names of the set status flags
 * @param obdCode the matching OBD code, if known (KWP engine codes below {@code 0x4000}: {@code P} + 4 hex digits)
 * @param failureType UDS only: the failure type byte
 * @param freezeFrame the environment data, or why it wasn't read
 */
public record FaultCode(String code, Protocol protocol, String record, int status, String statusText,
        List<String> statusFlags, Optional<String> obdCode, OptionalInt failureType, FreezeFrameData freezeFrame) {

    public FaultCode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(statusText, "statusText");
        statusFlags = List.copyOf(statusFlags);
        Objects.requireNonNull(obdCode, "obdCode");
        Objects.requireNonNull(failureType, "failureType");
        Objects.requireNonNull(freezeFrame, "freezeFrame");
        if (status < 0 || status > 0xFF) {
            throw new IllegalArgumentException("status must be a byte: " + status);
        }
    }

    /** The code with the UDS failure type, e.g. {@code P0401-00}; KWP codes as they are. */
    public String displayCode() {
        return failureType.isPresent() ? String.format("%s-%02X", code, failureType.getAsInt()) : code;
    }
}
