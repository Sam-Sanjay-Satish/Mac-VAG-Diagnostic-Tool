package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import java.util.Objects;

/**
 * A UDS DTC: 3 code bytes (2 bytes SAE J2012 code + failure type byte) and a status byte, e.g. record
 * {@code 04 01 00 2F} = {@code P0401}, failure type {@code 00}, status {@code 2F}. VCDS also shows a VW-specific
 * number for UDS faults; that mapping is for the VCDS log to tell.
 *
 * @param code the 24-bit value of the three code bytes, high byte first
 * @param status the status byte
 */
public record UdsDtc(int code, UdsDtcStatus status) implements Dtc {

    public UdsDtc {
        if (code < 0 || code > 0xFFFFFF) {
            throw new IllegalArgumentException("UDS DTC code must be 24 bits: " + code);
        }
        Objects.requireNonNull(status, "status");
    }

    public UdsDtc(int code, int status) {
        this(code, new UdsDtcStatus(status));
    }

    @Override
    public ApplicationProtocol protocol() {
        return ApplicationProtocol.UDS;
    }

    @Override
    public RawBytes codeBytes() {
        return new RawBytes(new byte[] {(byte) (code >> 16), (byte) (code >> 8), (byte) code});
    }

    @Override
    public RawBytes record() {
        return new RawBytes(new byte[] {(byte) (code >> 16), (byte) (code >> 8), (byte) code, (byte) status.raw()});
    }

    /** SAE J2012 code from the first two bytes, e.g. {@code P0401}. */
    @Override
    public String displayCode() {
        return DtcCodes.saeJ2012((code >> 16) & 0xFF, (code >> 8) & 0xFF);
    }

    /** The third code byte, the failure type (ISO 14229-1 DTCFailureTypeByte). */
    public int failureType() {
        return code & 0xFF;
    }

    @Override
    public String toString() {
        return String.format("%s-%02X [%s]", displayCode(), failureType(), record().hex());
    }
}
