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
 * <p><b>UNCONFIRMED</b> (from memory, to be corrected from the sniffing capture and the VCDS log):
 * <ul>
 *   <li>the list itself and the names;</li>
 *   <li>the TP 2.0 logical address of each module, assumed equal to the VCDS address (so module {@code 17} is set
 *       up with logical address {@code 0x17});</li>
 *   <li>which modules use ISO-TP + UDS instead. The engine (EDC17) and the transmission get ISO-TP
 *       {@code 7E0/7E8} and {@code 7E1/7E9} as a second link to try after TP 2.0.</li>
 * </ul>
 */
public final class ModuleAddress implements Comparable<ModuleAddress> {

    private static final Pattern TWO_HEX_DIGITS = Pattern.compile("[0-9A-Fa-f]{2}");

    private static final Map<Integer, ModuleAddress> KNOWN = new LinkedHashMap<>();

    static {
        kwp(0x01, "Engine", new ModuleLink.IsoTp(0x7E0, 0x7E8, Protocol.UDS));
        kwp(0x02, "Transmission", new ModuleLink.IsoTp(0x7E1, 0x7E9, Protocol.UDS));
        kwp(0x03, "ABS Brakes");
        kwp(0x08, "Auto HVAC");
        kwp(0x09, "Central Electrics");
        kwp(0x15, "Airbags");
        kwp(0x16, "Steering Wheel");
        kwp(0x17, "Instruments");
        kwp(0x19, "CAN Gateway");
        kwp(0x25, "Immobilizer");
        kwp(0x44, "Steering Assist");
        kwp(0x46, "Central Convenience");
        kwp(0x56, "Radio");
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

    /** A module reached over TP 2.0 at logical address = VCDS address (UNCONFIRMED), then the {@code extra} links. */
    private static void kwp(int address, String name, ModuleLink... extra) {
        List<ModuleLink> links = new java.util.ArrayList<>();
        links.add(new ModuleLink.Tp20(address));
        links.addAll(List.of(extra));
        KNOWN.put(address, new ModuleAddress(address, name, links));
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

    /** E.g. {@code Instruments}. UNCONFIRMED wording. */
    public String name() {
        return name;
    }

    /** The links to try, in order. UNCONFIRMED (see the class comment). */
    public List<ModuleLink> links() {
        return links;
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
