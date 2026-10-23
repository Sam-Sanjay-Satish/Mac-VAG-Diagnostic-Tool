package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Optional;

/**
 * Negative response codes this layer can name. Names follow ISO 14229-1 (UDS) and ISO 14230-3 (KWP2000), which
 * share these values. Written from memory of the standards; the descriptions are plain-language summaries. Any
 * other code is kept as its raw value in {@link NegativeResponse#code()}.
 */
public enum Nrc {
    GENERAL_REJECT(0x10, "generalReject", "the module rejected the request without giving a specific reason"),
    SERVICE_NOT_SUPPORTED(0x11, "serviceNotSupported", "the module does not support this service"),
    SUB_FUNCTION_NOT_SUPPORTED(0x12, "subFunctionNotSupported",
            "the module does not support this sub-function or parameter (KWP: or the format is invalid)"),
    INCORRECT_MESSAGE_LENGTH(0x13, "incorrectMessageLengthOrInvalidFormat",
            "the module did not accept the request's length or format"),
    BUSY_REPEAT_REQUEST(0x21, "busyRepeatRequest", "the module is busy; the request may be repeated later"),
    CONDITIONS_NOT_CORRECT(0x22, "conditionsNotCorrect",
            "the module can't do this in its current state (e.g. wrong session, engine running)"),
    REQUEST_SEQUENCE_ERROR(0x24, "requestSequenceError", "the module expected a different request first"),
    REQUEST_OUT_OF_RANGE(0x31, "requestOutOfRange",
            "the module does not support the requested identifier, record or value"),
    SECURITY_ACCESS_DENIED(0x33, "securityAccessDenied", "the module requires security access for this"),
    RESPONSE_PENDING(0x78, "requestCorrectlyReceived-ResponsePending",
            "the module is still working on the request and will answer later"),
    SUB_FUNCTION_NOT_SUPPORTED_IN_ACTIVE_SESSION(0x7E, "subFunctionNotSupportedInActiveSession",
            "the module does not support this sub-function in the current session"),
    SERVICE_NOT_SUPPORTED_IN_ACTIVE_SESSION(0x7F, "serviceNotSupportedInActiveSession",
            "the module does not support this service in the current session");

    private final int code;
    private final String isoName;
    private final String description;

    Nrc(int code, String isoName, String description) {
        this.code = code;
        this.isoName = isoName;
        this.description = description;
    }

    /** The byte as it appears on the bus, e.g. {@code 0x31}. */
    public int code() {
        return code;
    }

    /** The standard's name, e.g. {@code requestOutOfRange}. */
    public String isoName() {
        return isoName;
    }

    /** A plain-language explanation for reports. */
    public String description() {
        return description;
    }

    /** The named code for {@code code}, or empty if this layer doesn't know it. */
    public static Optional<Nrc> of(int code) {
        for (Nrc n : values()) {
            if (n.code == code) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }
}
