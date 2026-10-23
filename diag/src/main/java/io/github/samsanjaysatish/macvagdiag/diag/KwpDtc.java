package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.util.Objects;
import java.util.Optional;

/**
 * A KWP2000 DTC: a 16-bit VAG fault code and a status byte, e.g. record {@code 41 2C A3} = code 16684.
 *
 * @param code the 16-bit value of the two code bytes, high byte first
 * @param status the status byte
 * @param obdCode the matching OBD code (e.g. {@code P0300}) if a {@link VagCodeMapping} knows it; empty by default,
 *     because the VAG to P-code scheme is not hard-coded from memory
 */
public record KwpDtc(int code, KwpDtcStatus status, Optional<String> obdCode) implements Dtc {

    public KwpDtc {
        if (code < 0 || code > 0xFFFF) {
            throw new IllegalArgumentException("KWP DTC code must be 16 bits: " + code);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(obdCode, "obdCode");
    }

    /** A DTC with no OBD code mapping. */
    public KwpDtc(int code, int status) {
        this(code, new KwpDtcStatus(status), Optional.empty());
    }

    @Override
    public ApplicationProtocol protocol() {
        return ApplicationProtocol.KWP2000;
    }

    @Override
    public RawBytes codeBytes() {
        return new RawBytes(new byte[] {(byte) (code >> 8), (byte) code});
    }

    @Override
    public RawBytes record() {
        return new RawBytes(new byte[] {(byte) (code >> 8), (byte) code, (byte) status.raw()});
    }

    /** UNCONFIRMED: the 5-digit decimal of the 16-bit value, e.g. {@code 41 2C} = {@code 16684}. */
    @Override
    public String displayCode() {
        return DtcCodes.vagFiveDigit(code);
    }

    @Override
    public String toString() {
        return displayCode() + obdCode.map(c -> " (" + c + ")").orElse("") + " [" + record().hex() + "]";
    }
}
