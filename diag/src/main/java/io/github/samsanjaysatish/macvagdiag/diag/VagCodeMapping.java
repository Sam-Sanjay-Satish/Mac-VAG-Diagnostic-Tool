package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.Optional;

/**
 * <b>EXTENSION POINT, deliberately empty:</b> maps a VAG 5-digit KWP2000 fault code (e.g. 16684) to the OBD-II
 * code VCDS shows next to it (e.g. P0300).
 *
 * <p>VAG codes relate to P-codes by an offset scheme, but this project does <b>not</b> hard-code that scheme
 * from memory (root and {@code diag} CLAUDE.md). Implement it only once real pairs from the VCDS log exist, with a
 * test for every pair. Until then use {@link #NONE}, and {@link KwpDtc#obdCode()} stays empty.
 */
@FunctionalInterface
public interface VagCodeMapping {

    /** No mapping: every VAG code is shown on its own. The default. */
    VagCodeMapping NONE = vagCode -> Optional.empty();

    /** The OBD-II code for {@code vagCode}, or empty if unknown. */
    Optional<String> obdCode(int vagCode);
}
