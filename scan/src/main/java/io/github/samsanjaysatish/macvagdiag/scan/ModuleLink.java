package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Objects;

/**
 * How one module can be reached: which transport, and its addressing. A {@link ModuleAddress} lists the links to
 * try, in order. <b>Every link in the known list is UNCONFIRMED</b> until the sniffing capture shows which modules
 * use TP 2.0 and which ISO-TP (see {@code scan/CLAUDE.md}).
 */
public sealed interface ModuleLink permits ModuleLink.Tp20, ModuleLink.IsoTp {

    Protocol protocol();

    /** Readable, e.g. {@code KWP2000 over TP 2.0 (logical address 17)}. */
    String describe();

    /**
     * A VW TP 2.0 channel carrying KWP2000. UNCONFIRMED (transport's guide): the channel setup goes out on
     * {@code 200} and the module answers on {@code 200 + logicalAddress}.
     */
    record Tp20(int logicalAddress) implements ModuleLink {

        public static final int MIN_ADDRESS = 0x01;
        public static final int MAX_ADDRESS = 0x7F;

        public Tp20 {
            if (logicalAddress < MIN_ADDRESS || logicalAddress > MAX_ADDRESS) {
                throw new IllegalArgumentException(String.format("TP 2.0 logical address %02X out of range %02X..%02X",
                        logicalAddress, MIN_ADDRESS, MAX_ADDRESS));
            }
        }

        @Override
        public Protocol protocol() {
            return Protocol.KWP2000;
        }

        @Override
        public String describe() {
            return String.format("KWP2000 over TP 2.0 (logical address %02X)", logicalAddress);
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** An ISO-TP link with 11-bit IDs: requests on {@code requestId}, responses on {@code responseId}. */
    record IsoTp(int requestId, int responseId, Protocol protocol) implements ModuleLink {

        public IsoTp {
            Objects.requireNonNull(protocol, "protocol");
            if (requestId < 0 || requestId > 0x7FF || responseId < 0 || responseId > 0x7FF || requestId == responseId) {
                throw new IllegalArgumentException(String.format("Not a pair of distinct 11-bit CAN IDs: %X/%X",
                        requestId, responseId));
            }
        }

        @Override
        public String describe() {
            return String.format("%s over ISO-TP (%03X/%03X)", protocol, requestId, responseId);
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}
