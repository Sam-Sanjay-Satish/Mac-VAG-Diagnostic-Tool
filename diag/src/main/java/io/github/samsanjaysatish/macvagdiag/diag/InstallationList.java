package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The gateway's installation list, {@code 22 04 A1} → {@code 62 04 A1} followed by 4-byte entries
 * {@code [VCDS address] [TP 2.0 logical address] [status] [?]}, as VCDS reads it at the start of an Auto-Scan
 * (2026-11-13 capture: 35 entries, of which the 16 with a status other than {@code 00} are exactly VCDS's module
 * list).
 *
 * <p>Which modules to contact, and how, is {@code scan}'s business; this only decodes the bytes.
 *
 * @param entries every whole entry, in the order received (installed or not)
 * @param response the whole positive response, as received
 * @param undecoded bytes after the last whole 4-byte entry (normally empty)
 * @param warnings readable notes, e.g. a truncated entry
 */
public record InstallationList(List<Entry> entries, RawBytes response, RawBytes undecoded, List<String> warnings) {

    /** The gateway DID: {@code 04 A1}. */
    public static final int DID = 0x04A1;
    /** {@code 62 04 A1}. */
    static final int HEADER_LENGTH = 3;
    static final int ENTRY_LENGTH = 4;

    /**
     * One module of the list.
     *
     * @param address the VCDS address, e.g. {@code 0x17} for 17 Instruments
     * @param tp20LogicalAddress the TP 2.0 logical address the gateway lists for it (e.g. {@code 0x07} for 17);
     *     {@code 00} for a module with no TP 2.0 address (04 Steering Angle)
     * @param status the status byte
     * @param group the 4th byte, not decoded (UNCONFIRMED: looks like a bus / group: {@code 10}, {@code 11},
     *     {@code 15}, {@code 31})
     */
    public record Entry(int address, int tp20LogicalAddress, int status, int group) {

        /** The status byte values seen on the capture, with what VCDS printed for them. */
        public enum Status {
            /** {@code 00}: not in VCDS's list. */
            NOT_INSTALLED,
            /** {@code 01}: VCDS "OK 0000". */
            OK,
            /** {@code 05}: VCDS "Malfunction 0010". */
            MALFUNCTION,
            /** {@code 19}: VCDS "Cannot be reached 1100". */
            CANNOT_BE_REACHED,
            /** Any other value: not seen, UNCONFIRMED; use {@link #status()} raw. */
            OTHER
        }

        public Entry {
            for (int b : new int[] {address, tp20LogicalAddress, status, group}) {
                if (b < 0 || b > 0xFF) {
                    throw new IllegalArgumentException("Not a byte: " + b);
                }
            }
        }

        public Status statusKind() {
            return switch (status) {
                case 0x00 -> Status.NOT_INSTALLED;
                case 0x01 -> Status.OK;
                case 0x05 -> Status.MALFUNCTION;
                case 0x19 -> Status.CANNOT_BE_REACHED;
                default -> Status.OTHER;
            };
        }

        /** Status {@code 00} means not installed; anything else is in VCDS's module list. */
        public boolean installed() {
            return status != 0x00;
        }

        /**
         * VCDS's 4-digit status column: bits 4..1 of the status byte, e.g. {@code 05} → {@code 0010}, {@code 19} →
         * {@code 1100}. Confirmed for {@code 01}, {@code 05}, {@code 19}.
         */
        public String vcdsStatusBits() {
            return String.format("%4s", Integer.toBinaryString((status >> 1) & 0x0F)).replace(' ', '0');
        }

        /** The TP 2.0 logical address, or empty for {@code 00} (no TP 2.0 address listed). */
        public Optional<Integer> tp20Address() {
            return tp20LogicalAddress == 0 ? Optional.empty() : Optional.of(tp20LogicalAddress);
        }

        @Override
        public String toString() {
            return String.format("%02X -> TP 2.0 %02X, status %02X (%s), group %02X", address, tp20LogicalAddress,
                    status, statusKind(), group);
        }
    }

    public InstallationList {
        entries = List.copyOf(entries);
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(undecoded, "undecoded");
        warnings = List.copyOf(warnings);
    }

    /** The entries with a status other than {@code 00}, in list order. */
    public List<Entry> installed() {
        return entries.stream().filter(Entry::installed).toList();
    }

    /** The entry for VCDS address {@code address}, if listed. */
    public Optional<Entry> entry(int address) {
        return entries.stream().filter(e -> e.address() == address).findFirst();
    }

    /** True if nothing was left over and there are no warnings. */
    public boolean complete() {
        return undecoded.isEmpty() && warnings.isEmpty();
    }

    /** Parses the whole positive response {@code 62 04 A1 ...}. */
    static DiagResult<InstallationList> parse(RawBytes response) {
        if (response.length() < HEADER_LENGTH || response.byteAt(1) != DID >> 8 || response.byteAt(2) != (DID & 0xFF)) {
            return new DiagResult.Malformed<>("not '62 04 A1 ...'", response);
        }
        byte[] b = response.bytes();
        int whole = (b.length - HEADER_LENGTH) / ENTRY_LENGTH;
        List<Entry> entries = new ArrayList<>(whole);
        for (int i = 0; i < whole; i++) {
            int at = HEADER_LENGTH + i * ENTRY_LENGTH;
            entries.add(new Entry(Byte.toUnsignedInt(b[at]), Byte.toUnsignedInt(b[at + 1]), Byte.toUnsignedInt(b[at + 2]),
                    Byte.toUnsignedInt(b[at + 3])));
        }
        RawBytes undecoded = RawBytes.slice(b, HEADER_LENGTH + whole * ENTRY_LENGTH, b.length);
        List<String> warnings = undecoded.isEmpty() ? List.of()
                : List.of(undecoded.length() + " byte(s) after the last whole 4-byte entry (truncated?): " + undecoded.hex());
        return new DiagResult.Success<>(new InstallationList(entries, response, undecoded, warnings));
    }
}
