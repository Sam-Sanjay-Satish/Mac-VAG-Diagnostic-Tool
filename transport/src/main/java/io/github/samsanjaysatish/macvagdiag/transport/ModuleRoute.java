package io.github.samsanjaysatish.macvagdiag.transport;

import java.util.Objects;

/**
 * How to reach one module: which transport, and its addressing. The capture decides which modules use which
 * transport; {@code scan} picks the route, {@link ChannelFactory} opens it.
 *
 * <p>These records only validate their own ranges. {@link ChannelFactory} additionally refuses any route whose
 * CAN IDs are outside the expected diagnostic set (defense in depth), see {@link DiagnosticCanIds}.
 */
public sealed interface ModuleRoute {

    /** The application protocol this route carries. */
    ApplicationProtocol protocol();

    /**
     * A VW TP 2.0 channel carrying KWP2000 to the module with TP 2.0 logical address {@code moduleAddress}.
     *
     * <p>UNCONFIRMED (from memory, check against the capture): the setup request goes out on {@code 0x200} and
     * the module answers on {@code 0x200 + moduleAddress}; logical addresses are {@code 01..7F}. How VCDS
     * module numbers (01 engine, 19 gateway, ...) map to TP 2.0 logical addresses is for the capture to tell.
     */
    record Tp20(int moduleAddress) implements ModuleRoute {

        public Tp20 {
            if (moduleAddress < DiagnosticCanIds.TP20_MIN_MODULE_ADDRESS
                    || moduleAddress > DiagnosticCanIds.TP20_MAX_MODULE_ADDRESS) {
                throw new IllegalArgumentException(String.format("TP 2.0 module address 0x%02X out of range 0x%02X..0x%02X",
                        moduleAddress, DiagnosticCanIds.TP20_MIN_MODULE_ADDRESS, DiagnosticCanIds.TP20_MAX_MODULE_ADDRESS));
            }
        }

        @Override
        public ApplicationProtocol protocol() {
            return ApplicationProtocol.KWP2000;
        }

        @Override
        public String toString() {
            return String.format("TP2.0 module %02X", moduleAddress);
        }
    }

    /**
     * An ISO-TP (ISO 15765-2) link with 11-bit normal addressing: requests on {@code requestId}, responses on
     * {@code responseId}. Normally UDS; KWP2000 over ISO-TP is possible.
     *
     * <p>UNCONFIRMED: engine {@code 7E0 → 7E8} (and transmission {@code 7E1 → 7E9}) on this car. The factory
     * only accepts the request/response pairs in {@link DiagnosticCanIds}.
     */
    record IsoTp(int requestId, int responseId, ApplicationProtocol protocol) implements ModuleRoute {

        public IsoTp {
            Objects.requireNonNull(protocol, "protocol");
            checkStandardId(requestId, "request");
            checkStandardId(responseId, "response");
            if (requestId == responseId) {
                throw new IllegalArgumentException(String.format("Request and response ID are both 0x%03X", requestId));
            }
        }

        private static void checkStandardId(int id, String what) {
            if (id < 0 || id > 0x7FF) {
                throw new IllegalArgumentException(String.format("ISO-TP %s ID 0x%X is not an 11-bit CAN ID", what, id));
            }
        }

        @Override
        public String toString() {
            return String.format("ISO-TP %03X/%03X %s", requestId, responseId, protocol);
        }
    }
}
