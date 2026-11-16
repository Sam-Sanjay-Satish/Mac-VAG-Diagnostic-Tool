package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Optional;

/**
 * Maps a VAG KWP2000 fault code (the 16-bit value, e.g. {@code 2B A6} = 011174) to the OBD-II code VCDS shows
 * next to it (e.g. {@code P2BA6}).
 *
 * <p>Which mapping a module gets is {@code scan}'s choice (see {@link KwpOptions}): on the 2026-11-13 capture
 * only the engine's codes have P-codes; the gateway's ({@code 05 18} = 01304, {@code 08 1E} = 02078) have none,
 * although their values are just as small.
 */
@FunctionalInterface
public interface VagCodeMapping {

    /** No mapping: every VAG code is shown on its own. For modules without OBD codes (e.g. the gateway). */
    VagCodeMapping NONE = vagCode -> Optional.empty();

    /** Highest code (exclusive) {@link #P_CODE_HEX} maps. */
    int P_CODE_HEX_LIMIT = 0x4000;

    /**
     * {@code P} + the 16-bit value as four hex digits, for values below {@code 0x4000}: {@code 2B A6} →
     * {@code P2BA6}, {@code 00 87} → {@code P0087}. Confirmed for all 8 distinct engine codes in the capture
     * against the VCDS log. Values from {@code 0x4000} up use an older offset scheme that can't be checked on
     * this car, so they stay unmapped (UNCONFIRMED).
     */
    VagCodeMapping P_CODE_HEX = vagCode -> vagCode >= 0 && vagCode < P_CODE_HEX_LIMIT
            ? Optional.of(String.format("P%04X", vagCode))
            : Optional.empty();

    /** The OBD-II code for {@code vagCode}, or empty if unknown. */
    Optional<String> obdCode(int vagCode);
}
