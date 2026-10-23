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
     * KWP {@code 18 [status]}: read DTCs by status, one parameter byte. The ISO 14230 long form
     * {@code 18 [status] [group hi] [group lo]} is refused by the allowlist and can't be built here.
     */
    static DiagRequest kwpReadDtcsByStatus(int status) {
        return new DiagRequest(ApplicationProtocol.KWP2000, SID_KWP_READ_DTC_BY_STATUS, checkByte(status, "status"));
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

    /** UDS {@code 19 04 [DTC 3 bytes] [record]}: snapshot (freeze frame) record by DTC. */
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
