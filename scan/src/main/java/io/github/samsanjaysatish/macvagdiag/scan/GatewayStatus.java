package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.Objects;
import java.util.Optional;

/**
 * What the gateway's installation list ({@code 22 04 A1}, read at the start of an Auto-Scan) says about one
 * installed module: VCDS's "Status" column at the top of its Auto-Scan ("17-Instruments -- Status: Malfunction
 * 0010"). It is the gateway's view; the module's own answer is in {@link ModuleResult}.
 *
 * @param address the VCDS address the gateway listed, e.g. {@code 0x17}
 * @param module the known module at that address, empty if it isn't in {@link ModuleAddress#known()}
 * @param tp20LogicalAddress the TP 2.0 logical address the gateway lists for it ({@code 00} = none listed)
 * @param kind the status, decoded
 * @param statusByte the status byte as received
 */
public record GatewayStatus(int address, Optional<ModuleAddress> module, int tp20LogicalAddress, Kind kind,
        int statusByte) {

    /** The status byte values seen on the 2026-11-13 capture, with what VCDS printed for them. */
    public enum Kind {
        /** {@code 01}: VCDS "OK 0000". */
        OK("OK"),
        /** {@code 05}: VCDS "Malfunction 0010" (the module has fault codes stored). */
        MALFUNCTION("Malfunction"),
        /** {@code 19}: VCDS "Cannot be reached 1100" (installed, but the gateway can't talk to it). */
        CANNOT_BE_REACHED("Cannot be reached"),
        /** Any other non-zero value: never seen, UNCONFIRMED; see {@link #statusByte()}. */
        OTHER("Unknown status");

        private final String vcdsText;

        Kind(String vcdsText) {
            this.vcdsText = vcdsText;
        }

        /** VCDS's wording, e.g. {@code Malfunction}. */
        public String vcdsText() {
            return vcdsText;
        }
    }

    public GatewayStatus {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(kind, "kind");
        for (int b : new int[] {address, tp20LogicalAddress, statusByte}) {
            if (b < 0 || b > 0xFF) {
                throw new IllegalArgumentException("Not a byte: " + b);
            }
        }
    }

    /** The address as VCDS writes it, e.g. {@code 17}. */
    public String hex() {
        return String.format("%02X", address);
    }

    /**
     * VCDS's 4-digit status column: bits 4..1 of the status byte, e.g. {@code 05} → {@code 0010}, {@code 19} →
     * {@code 1100}.
     */
    public String vcdsStatusBits() {
        return String.format("%4s", Integer.toBinaryString((statusByte >> 1) & 0x0F)).replace(' ', '0');
    }

    /** E.g. {@code Malfunction 0010}, as VCDS prints it. */
    public String vcdsText() {
        return kind.vcdsText() + " " + vcdsStatusBits();
    }

    /** E.g. {@code 17 Instruments: Malfunction 0010}. */
    @Override
    public String toString() {
        return hex() + module.map(m -> " " + m.name()).orElse("") + ": " + vcdsText();
    }
}
