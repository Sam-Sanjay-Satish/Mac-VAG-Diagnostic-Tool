package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Objects;
import java.util.Optional;

/**
 * A negative response {@code 7F [SID] [NRC]} from the module.
 *
 * @param serviceId the service the module refused, e.g. {@code 0x19}
 * @param code the negative response code, e.g. {@code 0x31}; kept as is even when {@link Nrc} doesn't name it
 * @param raw the whole message as received (some modules append bytes after the NRC; they are kept here)
 */
public record NegativeResponse(int serviceId, int code, RawBytes raw) {

    public NegativeResponse {
        checkByte(serviceId, "serviceId");
        checkByte(code, "code");
        Objects.requireNonNull(raw, "raw");
    }

    /** A negative response whose raw bytes are exactly {@code 7F [serviceId] [code]}. */
    public static NegativeResponse of(int serviceId, int code) {
        return new NegativeResponse(serviceId, code, new RawBytes(new byte[] {(byte) 0x7F, (byte) serviceId, (byte) code}));
    }

    private static void checkByte(int value, String what) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(what + " must be a byte: " + value);
        }
    }

    /** The named code, or empty for a code this layer doesn't know. */
    public Optional<Nrc> nrc() {
        return Nrc.of(code);
    }

    /** E.g. {@code 7F 19 31 requestOutOfRange: the module does not support the requested identifier, ...}. */
    public String message() {
        return raw.hex() + " " + nrc().map(n -> n.isoName() + ": " + n.description())
                .orElse(String.format("unknown negative response code 0x%02X", code));
    }

    @Override
    public String toString() {
        return message();
    }
}
