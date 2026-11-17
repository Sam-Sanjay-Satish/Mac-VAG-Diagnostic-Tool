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
     * The exact transmit IDs a module may give us during TP 2.0 setup: the seven the target car's modules
     * negotiated in the 2026-11-13 capture, nothing else. {@code 740} (engine 01), {@code 760} (transmission 02),
     * {@code 790} (ABS 03), {@code 7A8} (steering assist 44, logical {@code 09}), {@code 32E} (gateway 19, logical
     * {@code 1F}), {@code 338} (central electrics 09, logical {@code 20}) and {@code 33C} (HVAC 08, logical
     * {@code 2C}). The last three were approved by the user on 2026-11-15; on 2026-11-17 the user approved
     * narrowing the old range {@code 740..7BF} to the four IDs seen in it, which also takes {@code 76B} and the
     * UDS response IDs {@code 77B 77E 77F 79B 79C} out of the TP 2.0 set. A setup reply negotiating anything else
     * is refused and nothing is sent on it.
     */
    static final Set<Integer> TP20_NEGOTIATED_TX = Set.of(0x740, 0x760, 0x790, 0x7A8, 0x32E, 0x338, 0x33C);

    /**
     * The known ISO-TP request ID → response ID pairs (11-bit normal addressing). Each request ID is allowed only
     * with its own response ID. The OBD functional broadcast {@code 7DF} and the functional ID {@code 700} are
     * never here.
     * <ul>
     *   <li>From the 2026-11-13 capture, approved by the user 2026-11-15: {@code 714 → 77E} (17 instruments),
     *       {@code 732 → 79C} (05 start authorisation), {@code 715 → 77F} (15 airbag), {@code 711 → 77B}
     *       (25 immobiliser), {@code 731 → 79B} (2B steering column lock), {@code 76B → 7D5} (77 telephone).</li>
     *   <li>{@code 7E0 → 7E8} and {@code 7E1 → 7E9} (from memory) were removed with the user's approval on
     *       2026-11-17: on this car they carried no diagnostics in the capture, only an OBD-II {@code 09 02}.</li>
     * </ul>
     */
    static final Map<Integer, Integer> ISO_TP_REQUEST_TO_RESPONSE = Map.of(
            0x714, 0x77E,
            0x732, 0x79C,
            0x715, 0x77F,
            0x711, 0x77B,
            0x731, 0x79B,
            0x76B, 0x7D5);

    private DiagnosticCanIds() {
    }

    static boolean isNegotiableTp20TxId(int id) {
        return TP20_NEGOTIATED_TX.contains(id);
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
