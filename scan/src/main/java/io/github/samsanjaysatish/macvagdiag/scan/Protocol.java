package io.github.samsanjaysatish.macvagdiag.scan;

/** The diagnostic application protocol a module speaks, as front ends see it. */
public enum Protocol {
    /** KWP2000 (ISO 14230-3 as VW uses it), normally over VW TP 2.0. */
    KWP2000,
    /** UDS (ISO 14229-1), normally over ISO-TP. */
    UDS
}
