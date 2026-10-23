package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.List;

/**
 * A VW KWP2000 DTC status byte.
 *
 * <p><b>UNCONFIRMED, every bit.</b> The layout here is from memory of how VCDS shows KWP faults (e.g.
 * {@code 35-10 ... Intermittent}): the top bit as "intermittent" and the low seven bits as the fault
 * symptom / elaboration code. ISO 14230-3 defines statusOfDTC differently (storage state, warning lamp and
 * readiness bits), and it isn't known which one VW modules follow. Until the capture and the VCDS log settle it,
 * {@link #raw()} is the authoritative value and these decodings are hints only.
 *
 * @param raw the byte as received
 */
public record KwpDtcStatus(int raw) implements DtcStatus {

    /** UNCONFIRMED: bit 7 = intermittent (VCDS "Intermittent", shown as {@code -10}); clear = static. */
    public static final int INTERMITTENT_BIT = 0x80;
    /** UNCONFIRMED: bits 0..6 = symptom / elaboration code (VCDS shows it in decimal, e.g. {@code 35}). */
    public static final int SYMPTOM_MASK = 0x7F;

    public KwpDtcStatus {
        if (raw < 0 || raw > 0xFF) {
            throw new IllegalArgumentException("Status must be a byte: " + raw);
        }
    }

    /** UNCONFIRMED: bit 7. */
    public boolean intermittent() {
        return (raw & INTERMITTENT_BIT) != 0;
    }

    /** UNCONFIRMED: bits 0..6, 0..127. */
    public int symptomCode() {
        return raw & SYMPTOM_MASK;
    }

    @Override
    public List<String> flagNames() {
        return intermittent() ? List.of("intermittent (UNCONFIRMED)") : List.of();
    }

    @Override
    public String toString() {
        return String.format("%02X (symptom %d%s, UNCONFIRMED)", raw, symptomCode(), intermittent() ? ", intermittent" : "");
    }
}
