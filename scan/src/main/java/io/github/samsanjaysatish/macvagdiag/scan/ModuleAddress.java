package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A VW module address as VCDS numbers them ({@code 01} engine, {@code 17} instruments, ...), with a readable name
 * and the links to try.
 *
 * <p><b>Only the known set exists.</b> There is no public constructor: an address comes from {@link #known()},
 * {@link #parse(String)} or {@link #of(int)}, which refuse anything outside the set. So a {@code ModuleAddress}
 * passed to {@link ScanService} has always been validated, and untrusted input (MCP arguments, URL paths) is
 * rejected before any request is built.
 *
 * <p><b>The table is the target car's</b> (2013 Jetta TDI), confirmed by the 2026-11-13 capture of a VCDS
 * Auto-Scan: the 16 modules the gateway's installation list reports as installed, with the link each one answered
 * on.
 * <ul>
 *   <li><b>TP 2.0 + KWP2000:</b> 01, 02, 03, 08, 09, 19, 44, at the TP 2.0 logical address the gateway lists, which
 *       is <i>not</i> the VCDS address for most of them (08 → {@code 2C}, 09 → {@code 20}, 19 → {@code 1F},
 *       44 → {@code 09}). 01 and 02 have no ISO-TP link: {@code 7E0/7E8} only carried an OBD-II VIN read.</li>
 *   <li><b>ISO-TP + UDS:</b> 05, 15, 17, 25, 2B, 77 on fixed CAN ID pairs (the list doesn't contain them). The
 *       gateway also lists a TP 2.0 address for these, but none answered there (VCDS tried and fell back), so we
 *       don't try TP 2.0 for them.</li>
 *   <li><b>2E Media Player and 56 Radio:</b> installed, but "Cannot be reached" on this car (they answered nothing
 *       to VCDS). Kept with their listed TP 2.0 address so a scan reports them like VCDS does.</li>
 *   <li><b>04 Steering Angle:</b> no link of its own. VCDS never contacts it; its status comes only from the
 *       gateway's installation list.</li>
 * </ul>
 * 16 Steering Wheel and 46 Central Convenience (in the first, from-memory table) are not installed on this car and
 * were dropped: their links were never seen. A module the gateway lists that isn't here is reported by the
 * Auto-Scan as installed but not in the table, and not contacted.
 */
public final class ModuleAddress implements Comparable<ModuleAddress> {

    private static final Pattern TWO_HEX_DIGITS = Pattern.compile("[0-9A-Fa-f]{2}");

    private static final Map<Integer, ModuleAddress> KNOWN = new LinkedHashMap<>();

    static {
        tp20(0x01, "Engine", 0x01);
        tp20(0x02, "Transmission", 0x02);
        tp20(0x03, "ABS Brakes", 0x03);
        gatewayOnly(0x04, "Steering Angle");
        uds(0x05, "Access/Start Authorization", 0x732, 0x79C);
        tp20(0x08, "Auto HVAC", 0x2C);
        tp20(0x09, "Central Electrics", 0x20);
        uds(0x15, "Airbags", 0x715, 0x77F);
        uds(0x17, "Instruments", 0x714, 0x77E);
        tp20(0x19, "CAN Gateway", 0x1F);
        uds(0x25, "Immobilizer", 0x711, 0x77B);
        uds(0x2B, "Steering Column Lock", 0x731, 0x79B);
        tp20(0x2E, "Media Player", 0x54);
        tp20(0x44, "Steering Assist", 0x09);
        tp20(0x56, "Radio", 0x52);
        uds(0x77, "Telephone", 0x76B, 0x7D5);
    }

    private static final List<ModuleAddress> KNOWN_LIST = List.copyOf(KNOWN.values());

    public static final ModuleAddress ENGINE = KNOWN.get(0x01);
    public static final ModuleAddress TRANSMISSION = KNOWN.get(0x02);
    public static final ModuleAddress INSTRUMENTS = KNOWN.get(0x17);
    public static final ModuleAddress GATEWAY = KNOWN.get(0x19);

    private final int address;
    private final String name;
    private final List<ModuleLink> links;

    private ModuleAddress(int address, String name, List<ModuleLink> links) {
        this.address = address;
        this.name = name;
        this.links = List.copyOf(links);
    }

    /** KWP2000 over TP 2.0 at the logical address the gateway lists for it. */
    private static void tp20(int address, String name, int logicalAddress) {
        put(address, name, List.of(new ModuleLink.Tp20(logicalAddress)));
    }

    /** UDS over ISO-TP on a fixed request/response ID pair. */
    private static void uds(int address, String name, int requestId, int responseId) {
        put(address, name, List.of(new ModuleLink.IsoTp(requestId, responseId, Protocol.UDS)));
    }

    /** No link of its own: only the gateway's installation list says anything about it. */
    private static void gatewayOnly(int address, String name) {
        put(address, name, List.of());
    }

    private static void put(int address, String name, List<ModuleLink> links) {
        if (KNOWN.put(address, new ModuleAddress(address, name, links)) != null) {
            throw new IllegalStateException("Duplicate module " + address);
        }
    }

    /** Every known module, in address order. */
    public static List<ModuleAddress> known() {
        return KNOWN_LIST;
    }

    /**
     * Parses untrusted text strictly: exactly two hex digits, either case ({@code "08"}, {@code "1a"}), naming a
     * known module. Nothing else is accepted: no {@code 0x}, no spaces, no single digit, no sign.
     *
     * @throws InvalidModuleAddressException if the text is malformed or names no known module
     */
    public static ModuleAddress parse(String text) {
        if (text == null) {
            throw new InvalidModuleAddressException(null, "no module address given; " + hint());
        }
        if (!TWO_HEX_DIGITS.matcher(text).matches()) {
            throw new InvalidModuleAddressException(text, "module address " + Json.preview(text)
                    + " is not two hex digits like \"08\"; " + hint());
        }
        return of(Integer.parseInt(text, 16));
    }

    /** {@link #of(int)} without the exception. */
    public static Optional<ModuleAddress> tryOf(int address) {
        return Optional.ofNullable(KNOWN.get(address));
    }

    /** {@link #parse(String)} without the exception. */
    public static Optional<ModuleAddress> tryParse(String text) {
        try {
            return Optional.of(parse(text));
        } catch (InvalidModuleAddressException e) {
            return Optional.empty();
        }
    }

    /**
     * The known module at {@code address}.
     *
     * @throws InvalidModuleAddressException if no known module has that address
     */
    public static ModuleAddress of(int address) {
        ModuleAddress m = KNOWN.get(address);
        if (m == null) {
            String text = address >= 0 && address <= 0xFF ? String.format("%02X", address) : Integer.toString(address);
            throw new InvalidModuleAddressException(text, "module " + text + " is not a known module; " + hint());
        }
        return m;
    }

    private static String hint() {
        StringBuilder b = new StringBuilder("known modules: ");
        for (ModuleAddress m : KNOWN_LIST) {
            if (b.length() > "known modules: ".length()) {
                b.append(", ");
            }
            b.append(m.hex());
        }
        return b.toString();
    }

    /** The address as a number, e.g. {@code 0x17}. */
    public int address() {
        return address;
    }

    /** The address as VCDS writes it, e.g. {@code 17}. */
    public String hex() {
        return String.format("%02X", address);
    }

    /** E.g. {@code Instruments}. Our own wording, close to VCDS's. */
    public String name() {
        return name;
    }

    /** The links to try, in order (see the class comment). Empty for a module with no link of its own (04). */
    public List<ModuleLink> links() {
        return links;
    }

    /** True if the module can be contacted at all; false for 04 Steering Angle (gateway status only). */
    public boolean hasLink() {
        return !links.isEmpty();
    }

    @Override
    public int compareTo(ModuleAddress o) {
        return Integer.compare(address, o.address);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModuleAddress m && m.address == address;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(address);
    }

    /** E.g. {@code 17 Instruments}. */
    @Override
    public String toString() {
        return hex() + " " + name;
    }
}
