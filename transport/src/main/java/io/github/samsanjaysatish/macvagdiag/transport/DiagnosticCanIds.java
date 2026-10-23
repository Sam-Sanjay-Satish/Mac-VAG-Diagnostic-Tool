package io.github.samsanjaysatish.macvagdiag.transport;

import java.util.Map;
import java.util.Optional;

/**
 * The expected diagnostic CAN ID set (defense in depth behind the {@link RequestGuard}). The transport never
 * transmits on any other ID, and never on a 29-bit ID.
 *
 * <p><b>Every value here is UNCONFIRMED</b> (from memory) until checked against the sniffing capture. They are
 * kept in this one class so the capture can correct them in one place.
 */
final class DiagnosticCanIds {

    /** UNCONFIRMED: TP 2.0 channel setup requests go out on {@code 200}. */
    static final int TP20_SETUP_REQUEST_ID = 0x200;
    /** UNCONFIRMED: the module answers the setup on {@code 200 + module address}. */
    static final int TP20_SETUP_RESPONSE_BASE = 0x200;
    /** UNCONFIRMED: range of TP 2.0 logical module addresses we will set up a channel to. */
    static final int TP20_MIN_MODULE_ADDRESS = 0x01;
    static final int TP20_MAX_MODULE_ADDRESS = 0x7F;
    /** UNCONFIRMED: the receive ID we propose in the setup request ({@code 00 03} → {@code 300}). */
    static final int TP20_TESTER_RX_ID = 0x300;
    /**
     * UNCONFIRMED: the range a module may tell us to transmit on during setup (e.g. {@code 740}). A setup reply
     * negotiating anything else is refused and nothing is sent on it. Deliberately narrow, excluding the OBD
     * functional ID {@code 7DF} and the UDS IDs {@code 7E0..7EF}.
     */
    static final int TP20_NEGOTIATED_TX_MIN = 0x740;
    static final int TP20_NEGOTIATED_TX_MAX = 0x7BF;

    /**
     * UNCONFIRMED: the known UDS request ID → response ID pairs (11-bit normal addressing). Engine (EDC17)
     * {@code 7E0 → 7E8}, transmission {@code 7E1 → 7E9}. The OBD functional broadcast {@code 7DF} is never here.
     */
    static final Map<Integer, Integer> ISO_TP_REQUEST_TO_RESPONSE = Map.of(
            0x7E0, 0x7E8,
            0x7E1, 0x7E9);

    private DiagnosticCanIds() {
    }

    static boolean isNegotiableTp20TxId(int id) {
        return id >= TP20_NEGOTIATED_TX_MIN && id <= TP20_NEGOTIATED_TX_MAX;
    }

    static boolean isKnownIsoTpRequestId(int id) {
        return ISO_TP_REQUEST_TO_RESPONSE.containsKey(id);
    }

    static Optional<Integer> isoTpResponseIdFor(int requestId) {
        return Optional.ofNullable(ISO_TP_REQUEST_TO_RESPONSE.get(requestId));
    }

    /** True if the transport may ever transmit on {@code id} (standard 11-bit). */
    static boolean isPermittedTransmitId(int id) {
        return id == TP20_SETUP_REQUEST_ID || isNegotiableTp20TxId(id) || isKnownIsoTpRequestId(id);
    }
}
