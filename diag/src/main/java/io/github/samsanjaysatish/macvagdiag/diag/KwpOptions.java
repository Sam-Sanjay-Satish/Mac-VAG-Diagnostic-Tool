package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Objects;

/**
 * Per-module choices for {@link KwpDiagnosticService} that the bytes can't tell and {@code scan} decides (it
 * knows which module it is talking to):
 *
 * @param dtcRequest which of the two allowlisted {@code 18} requests reads the DTCs
 * @param codeMapping VAG → OBD code mapping for {@link KwpDtc#obdCode()}
 */
public record KwpOptions(DtcRequest dtcRequest, VagCodeMapping codeMapping) {

    /**
     * The two {@code 18} requests VCDS sent on the 2026-11-13 capture, and the only two the allowlist has
     * (ISO 14230 {@code 18 [status] [group hi] [group lo]}, group {@code FF 00} = all). Why VCDS uses {@code 00}
     * for the engine only is unknown.
     */
    public enum DtcRequest {
        /** {@code 18 00 FF 00}: what VCDS sent to 01 Engine. */
        STATUS_00(0x00),
        /** {@code 18 02 FF 00}: what VCDS sent to every other KWP module and the gateway. */
        STATUS_02(0x02);

        private final int statusByte;

        DtcRequest(int statusByte) {
            this.statusByte = statusByte;
        }

        /** The byte after {@code 18}. */
        public int statusByte() {
            return statusByte;
        }
    }

    /** Engine (01): {@code 18 00 FF 00}, P-codes from {@link VagCodeMapping#P_CODE_HEX}. */
    public static final KwpOptions ENGINE = new KwpOptions(DtcRequest.STATUS_00, VagCodeMapping.P_CODE_HEX);

    /** Every other module: {@code 18 02 FF 00}, no OBD codes. The default. */
    public static final KwpOptions DEFAULT = new KwpOptions(DtcRequest.STATUS_02, VagCodeMapping.NONE);

    public KwpOptions {
        Objects.requireNonNull(dtcRequest, "dtcRequest");
        Objects.requireNonNull(codeMapping, "codeMapping");
    }
}
