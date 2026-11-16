package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * A VW KWP2000 DTC status byte, as the 2026-11-13 capture and the VCDS log of the same car show it (12 engine
 * records and 2 gateway records, every one matched). VCDS prints the byte raw as 8 bits ("Fault Status:
 * 01100001").
 *
 * <ul>
 *   <li>Bits 0..4: the fault type number VCDS prints after the P-code or the code ({@code P0401 - 001},
 *       {@code 004 - No Signal/Communication}). Seen: 0, 1, 2, 4, 6.</li>
 *   <li>Bit 5: <b>UNCONFIRMED</b>, set in every record seen; meaning unknown, kept in {@link #raw()} only.</li>
 *   <li>Bit 6 <b>clear</b>: VCDS "Intermittent".</li>
 *   <li>Bit 7: VCDS "MIL ON".</li>
 * </ul>
 *
 * @param raw the byte as received
 */
public record KwpDtcStatus(int raw) implements DtcStatus {

    /** Bits 0..4: the fault type number ({@code - 00x} in VCDS). */
    public static final int FAULT_TYPE_MASK = 0x1F;
    /** UNCONFIRMED: bit 5, set in every record of the capture; meaning unknown. */
    public static final int UNKNOWN_BIT_5 = 0x20;
    /** Bit 6: set = present ("static"); clear = VCDS "Intermittent". */
    public static final int NOT_INTERMITTENT_BIT = 0x40;
    /** Bit 7: VCDS "MIL ON". */
    public static final int MIL_ON_BIT = 0x80;

    /** VCDS's words, as printed after the fault type. */
    public static final String INTERMITTENT = "Intermittent";
    public static final String MIL_ON = "MIL ON";

    public KwpDtcStatus {
        if (raw < 0 || raw > 0xFF) {
            throw new IllegalArgumentException("Status must be a byte: " + raw);
        }
    }

    /** Bits 0..4, 0..31: the number VCDS prints as {@code 000}..{@code 031}. */
    public int faultType() {
        return raw & FAULT_TYPE_MASK;
    }

    /** {@link #faultType()} as VCDS prints it, three digits, e.g. {@code 002}. */
    public String faultTypeText() {
        return String.format("%03d", faultType());
    }

    /** Bit 6 clear. */
    public boolean intermittent() {
        return (raw & NOT_INTERMITTENT_BIT) == 0;
    }

    /** Bit 7. */
    public boolean milOn() {
        return (raw & MIL_ON_BIT) != 0;
    }

    /** VCDS's words for this status, lowest bit first: {@code Intermittent}, {@code MIL ON}; empty for neither. */
    @Override
    public List<String> flagNames() {
        List<String> names = new ArrayList<>(2);
        if (intermittent()) {
            names.add(INTERMITTENT);
        }
        if (milOn()) {
            names.add(MIL_ON);
        }
        return List.copyOf(names);
    }

    /** The raw byte as 8 bits, highest first, as VCDS prints "Fault Status", e.g. {@code 01100001}. */
    public String bits() {
        return String.format("%8s", Integer.toBinaryString(raw)).replace(' ', '0');
    }

    /** E.g. {@code 61 (01100001, type 001)} or {@code E0 (11100000, type 000, MIL ON)}. */
    @Override
    public String toString() {
        List<String> flags = flagNames();
        return String.format("%02X (%s, type %s%s)", raw, bits(), faultTypeText(),
                flags.isEmpty() ? "" : ", " + String.join(", ", flags));
    }
}
