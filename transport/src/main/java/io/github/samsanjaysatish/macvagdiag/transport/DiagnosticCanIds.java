package io.github.samsanjaysatish.macvagdiag.transport;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The expected diagnostic CAN ID set (defense in depth behind the {@link RequestGuard}). The transport never
 * transmits on any other ID, and never on a 29-bit ID.
 *
 * <p>The values first came from memory. The 2026-11-13 sniffing capture of the target car confirmed the TP 2.0
 * setup IDs and showed the IDs the car really uses; the user approved adding those on 2026-11-15 (exact IDs and
 * exact pairs only; the functional ID {@code 700} was not approved). They are kept in this one class so any further
 * correction happens in one place. Changing this set needs the user's explicit approval.
 */
final class DiagnosticCanIds {

    /** TP 2.0 channel setup requests go out on {@code 200} (confirmed 2026-11-13). */
    static final int TP20_SETUP_REQUEST_ID = 0x200;
    /** The module answers the setup on {@code 200 + module address} (confirmed 2026-11-13). */
    static final int TP20_SETUP_RESPONSE_BASE = 0x200;
    /** UNCONFIRMED: range of TP 2.0 logical module addresses we will set up a channel to. */
    static final int TP20_MIN_MODULE_ADDRESS = 0x01;
    static final int TP20_MAX_MODULE_ADDRESS = 0x7F;
    /** The receive ID we propose in the setup request ({@code 00 03} → {@code 300}); every module used it (2026-11-13). */
    static final int TP20_TESTER_RX_ID = 0x300;
    /**
     * The range a module may tell us to transmit on during setup (e.g. {@code 740}). The capture saw {@code 740}
     * (01), {@code 760} (02), {@code 790} (03) and {@code 7A8} (44) in it. Deliberately narrow, excluding the OBD
     * functional ID {@code 7DF} and the UDS IDs {@code 7E0..7EF}.
     */
    static final int TP20_NEGOTIATED_TX_MIN = 0x740;
    static final int TP20_NEGOTIATED_TX_MAX = 0x7BF;
    /**
     * TP 2.0 transmit IDs outside that range that modules negotiated in the 2026-11-13 capture, accepted as exact
     * IDs only (approved by the user 2026-11-15): {@code 32E} (gateway 19, logical {@code 1F}), {@code 338}
     * (central electrics 09, logical {@code 20}), {@code 33C} (HVAC 08, logical {@code 2C}). A setup reply
     * negotiating anything else is refused and nothing is sent on it.
     */
    static final Set<Integer> TP20_NEGOTIATED_TX_EXACT = Set.of(0x32E, 0x338, 0x33C);

    /**
     * The known ISO-TP request ID → response ID pairs (11-bit normal addressing). Each request ID is allowed only
     * with its own response ID. The OBD functional broadcast {@code 7DF} and the functional ID {@code 700} are
     * never here.
     * <ul>
     *   <li>From the 2026-11-13 capture, approved by the user 2026-11-15: {@code 714 → 77E} (17 instruments),
     *       {@code 732 → 79C} (05 start authorisation), {@code 715 → 77F} (15 airbag), {@code 711 → 77B}
     *       (25 immobiliser), {@code 731 → 79B} (2B steering column lock), {@code 76B → 7D5} (77 telephone).</li>
     *   <li>From memory, UNCONFIRMED: {@code 7E0 → 7E8} and {@code 7E1 → 7E9}. On this car they carried no
     *       diagnostics in the capture (only an OBD-II {@code 09 02} on {@code 7E0}); kept because {@code scan}'s
     *       module table and demo car still use {@code 7E0}. Removing them is the user's decision.</li>
     * </ul>
     */
    static final Map<Integer, Integer> ISO_TP_REQUEST_TO_RESPONSE = Map.of(
            0x714, 0x77E,
            0x732, 0x79C,
            0x715, 0x77F,
            0x711, 0x77B,
            0x731, 0x79B,
            0x76B, 0x7D5,
            0x7E0, 0x7E8,
            0x7E1, 0x7E9);

    private DiagnosticCanIds() {
    }

    static boolean isNegotiableTp20TxId(int id) {
        return (id >= TP20_NEGOTIATED_TX_MIN && id <= TP20_NEGOTIATED_TX_MAX) || TP20_NEGOTIATED_TX_EXACT.contains(id);
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
