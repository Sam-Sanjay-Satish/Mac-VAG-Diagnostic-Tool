package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.util.Objects;
import java.util.Optional;

/**
 * A KWP2000 DTC: a 16-bit VAG fault code and a status byte, e.g. engine record {@code 04 01 61} = 001025
 * (P0401), status {@code 61}.
 *
 * @param code the 16-bit value of the two code bytes, high byte first
 * @param status the status byte
 * @param obdCode the matching OBD code (e.g. {@code P0401}) if the module's {@link VagCodeMapping} knows it
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

    /**
     * The decimal of the 16-bit value as VCDS shows it: 6 digits when there is an OBD code ({@code 011174}),
     * 5 otherwise ({@code 01304}). See {@link DtcCodes#vagSixDigit}.
     */
    @Override
    public String displayCode() {
        return obdCode.isPresent() ? DtcCodes.vagSixDigit(code) : DtcCodes.vagFiveDigit(code);
    }

    @Override
    public String toString() {
        return displayCode() + obdCode.map(c -> " (" + c + ")").orElse("") + " [" + record().hex() + "]";
    }
}
