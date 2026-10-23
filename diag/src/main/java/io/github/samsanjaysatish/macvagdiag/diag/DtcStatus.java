package io.github.samsanjaysatish.macvagdiag.diag;

import java.util.List;

/**
 * A DTC's status byte: always the raw byte, plus what this layer can decode from it. KWP2000 and UDS status
 * bytes mean different things, so they are separate types.
 */
public sealed interface DtcStatus permits UdsDtcStatus, KwpDtcStatus {

    /** The status byte as received, 0..255. */
    int raw();

    /** Readable names of the flags that are set, lowest bit first. */
    List<String> flagNames();
}
