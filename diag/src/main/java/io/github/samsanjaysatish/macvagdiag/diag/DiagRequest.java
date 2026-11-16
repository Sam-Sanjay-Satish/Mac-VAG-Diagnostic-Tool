package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.can.Hex;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;
import io.github.samsanjaysatish.macvagdiag.transport.RequestGuard;
import java.util.Objects;

/**
 * One request this layer can send. <b>The only way requests are made in {@code diag}:</b> the constructor is
 * private, the static factories below are the complete set, and the constructor checks every instance against
 * {@link RequestGuard#isAllowed} as it is built. So a {@code DiagRequest} the guard would refuse cannot exist,
 * and the channel's {@code ForbiddenRequestException} is impossible by construction.
 *
 * <p>Package-private on purpose: nothing above {@code diag} can build or send a request (root rule 5).
 * {@code RequestAllowlistTest} enumerates every factory and sweeps its parameters through the guard.
 */
final class DiagRequest {

    // Service IDs, as on the bus.
    static final int SID_START_SESSION = 0x10;
    static final int SID_KWP_READ_FREEZE_FRAME = 0x12;
    static final int SID_KWP_READ_DTC_BY_STATUS = 0x18;
    static final int SID_UDS_READ_DTC_INFORMATION = 0x19;
    static final int SID_KWP_READ_ECU_IDENTIFICATION = 0x1A;
    static final int SID_UDS_READ_DATA_BY_IDENTIFIER = 0x22;
    static final int SID_TESTER_PRESENT = 0x3E;

    /** Positive response SID = request SID + 0x40. */
    static final int POSITIVE_RESPONSE_OFFSET = 0x40;
    /** Negative response: {@code 7F [SID] [NRC]}. */
    static final int NEGATIVE_RESPONSE_SID = 0x7F;

    // Session bytes (sub-functions), as on the bus. UNCONFIRMED which ones VCDS uses per module.
    static final int KWP_SESSION_VW_DIAGNOSTIC = 0x89;
    static final int UDS_SESSION_DEFAULT = 0x01;
    static final int UDS_SESSION_EXTENDED = 0x03;

    /** KWP {@code 18 [status] FF 00}: group {@code FF 00} = all DTCs. */
    static final int KWP_DTC_GROUP_ALL_HI = 0xFF;
    static final int KWP_DTC_GROUP_ALL_LO = 0x00;
    /** KWP {@code 12 00 04 ...}: freeze frame number {@code 00}. */
    static final int KWP_FREEZE_FRAME_NUMBER = 0x00;
    /** KWP {@code 12 00 04 ...}: record access method {@code 04} = by DTC. */
    static final int KWP_RECORD_ACCESS_BY_DTC = 0x04;

    /** UDS {@code 3E 00}: tester present, positive response wanted ({@code 7E 00}). */
    static final int UDS_TESTER_PRESENT_SUB_FUNCTION = 0x00;

    // UDS 19 sub-functions.
    static final int UDS_REPORT_DTC_BY_STATUS_MASK = 0x02;
    static final int UDS_REPORT_SNAPSHOT_BY_DTC = 0x04;
    static final int UDS_REPORT_EXTENDED_DATA_BY_DTC = 0x06;

    private final ApplicationProtocol protocol;
    private final byte[] bytes;

    private DiagRequest(ApplicationProtocol protocol, int... values) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) checkByte(values[i], "byte " + i);
        }
        if (!RequestGuard.isAllowed(protocol, b)) {
            // A bug in this class: it must only build allowlisted shapes. Never reaches the channel.
            throw new IllegalStateException("diag built a request the read-only allowlist refuses: " + protocol + " " + Hex.format(b));
        }
        this.bytes = b;
    }

    // ---- KWP2000 ----

    /** KWP {@code 10 89}: VW diagnostic session. */
    static DiagRequest kwpStartVwDiagnosticSession() {
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_START_SESSION, KWP_SESSION_VW_DIAGNOSTIC);
    }

    /** KWP {@code 3E}: tester present (no parameter byte; the allowlist allows none). */
    static DiagRequest kwpTesterPresent() {
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_TESTER_PRESENT);
    }

    /** KWP {@code 1A [option]}: read ECU identification, one option byte. */
    static DiagRequest kwpReadEcuIdentification(int option) {
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_KWP_READ_ECU_IDENTIFICATION, checkByte(option, "option"));
    }

    /**
     * KWP {@code 18 00 FF 00} or {@code 18 02 FF 00}: read DTCs by status, all groups (ISO 14230 4-byte form).
     * Exactly the two requests VCDS sent on the 2026-11-13 capture and the allowlist has (approved 2026-11-15).
     * The 2-byte {@code 18 xx} form is allowlisted too but VCDS never used it, so it isn't built here.
     */
    static DiagRequest kwpReadDtcsByStatus(KwpOptions.DtcRequest request) {
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_KWP_READ_DTC_BY_STATUS, request.statusByte(),
                KWP_DTC_GROUP_ALL_HI, KWP_DTC_GROUP_ALL_LO);
    }

    /**
     * KWP {@code 12 00 04 [hi] [lo]}: freeze frame {@code 00} by DTC (record access method {@code 04}) for the
     * 16-bit code. The only 5-byte {@code 12} form the allowlist has (approved 2026-11-15).
     */
    static DiagRequest kwpReadFreezeFrameByDtc(int code16) {
        if (code16 < 0 || code16 > 0xFFFF) {
            throw new IllegalArgumentException("KWP DTC must be 16 bits: " + code16);
        }
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_KWP_READ_FREEZE_FRAME, KWP_FREEZE_FRAME_NUMBER,
                KWP_RECORD_ACCESS_BY_DTC, (code16 >> 8) & 0xFF, code16 & 0xFF);
    }

    /**
     * KWP2000 {@code 22 [DID hi] [DID lo]}: one UDS-style data identifier over a TP 2.0 / KWP2000 channel, as
     * 03, 09 and 19 answer it on the capture (approved 2026-11-15). One DID per request.
     */
    static DiagRequest kwpReadDataByIdentifier(int did) {
        if (did < 0 || did > 0xFFFF) {
            throw new IllegalArgumentException("DID must be 16 bits: " + did);
        }
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_UDS_READ_DATA_BY_IDENTIFIER, (did >> 8) & 0xFF, did & 0xFF);
    }

    // ---- UDS ----

    /** UDS {@code 10 01} or {@code 10 03}. */
    static DiagRequest udsStartSession(DiagnosticSession session) {
        return switch (session) {
            case UDS_DEFAULT -> new DiagRequest(ApplicationProtocol.UDS, SID_START_SESSION, UDS_SESSION_DEFAULT);
            case UDS_EXTENDED -> new DiagRequest(ApplicationProtocol.UDS, SID_START_SESSION, UDS_SESSION_EXTENDED);
            case KWP_VW_DIAGNOSTIC -> throw new IllegalArgumentException(session + " is not a UDS session");
        };
    }

    /** UDS {@code 3E 00}: tester present with a positive response. */
    static DiagRequest udsTesterPresent() {
        return new DiagRequest(ApplicationProtocol.UDS, SID_TESTER_PRESENT, UDS_TESTER_PRESENT_SUB_FUNCTION);
    }

    /** UDS {@code 19 02 [mask]}: report DTCs by status mask. */
    static DiagRequest udsReadDtcsByStatusMask(int mask) {
        return new DiagRequest(ApplicationProtocol.UDS, SID_UDS_READ_DTC_INFORMATION, UDS_REPORT_DTC_BY_STATUS_MASK,
                checkByte(mask, "mask"));
    }

    /**
     * UDS {@code 19 04 [DTC 3 bytes] [record]}: snapshot (freeze frame) record by DTC. Allowlisted, but not sent by
     * {@code readFreezeFrame} since 2026-11-16 (VCDS reads {@code 19 06} only on this car); kept for later use.
     */
    static DiagRequest udsReadSnapshot(int dtc, int record) {
        return udsByDtc(UDS_REPORT_SNAPSHOT_BY_DTC, dtc, record);
    }

    /** UDS {@code 19 06 [DTC 3 bytes] [record]}: extended data record by DTC. */
    static DiagRequest udsReadExtendedData(int dtc, int record) {
        return udsByDtc(UDS_REPORT_EXTENDED_DATA_BY_DTC, dtc, record);
    }

    private static DiagRequest udsByDtc(int subFunction, int dtc, int record) {
        if (dtc < 0 || dtc > 0xFFFFFF) {
            throw new IllegalArgumentException("DTC must be 24 bits: " + dtc);
        }
        return new DiagRequest(ApplicationProtocol.UDS, SID_UDS_READ_DTC_INFORMATION, subFunction,
                (dtc >> 16) & 0xFF, (dtc >> 8) & 0xFF, dtc & 0xFF, checkByte(record, "record"));
    }

    /** UDS {@code 22 [DID hi] [DID lo]}: read one data identifier. */
    static DiagRequest udsReadDataByIdentifier(int did) {
        if (did < 0 || did > 0xFFFF) {
            throw new IllegalArgumentException("DID must be 16 bits: " + did);
        }
        return new DiagRequest(ApplicationProtocol.UDS, SID_UDS_READ_DATA_BY_IDENTIFIER, (did >> 8) & 0xFF, did & 0xFF);
    }

    // ---- accessors ----

    ApplicationProtocol protocol() {
        return protocol;
    }

    /** A copy of the request bytes. */
    byte[] bytes() {
        return bytes.clone();
    }

    int serviceId() {
        return Byte.toUnsignedInt(bytes[0]);
    }

    int positiveResponseSid() {
        return serviceId() + POSITIVE_RESPONSE_OFFSET;
    }

    /** Bus-style hex, e.g. {@code 19 02 FF}. */
    String hex() {
        return Hex.format(bytes);
    }

    @Override
    public String toString() {
        return protocol + " " + hex();
    }

    private static int checkByte(int value, String what) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(what + " must be a byte: " + value);
        }
        return value;
    }
}
