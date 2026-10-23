package io.github.samsanjaysatish.macvagdiag.diag;

import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;

/** One diagnostic trouble code as read from a module. */
public sealed interface Dtc permits KwpDtc, UdsDtc {

    ApplicationProtocol protocol();

    /** The code bytes as received: 2 for KWP2000, 3 for UDS. */
    RawBytes codeBytes();

    /** The whole DTC record as received: the code bytes followed by the status byte. Nothing is dropped. */
    RawBytes record();

    /** The code as shown to people: VAG 5-digit for KWP2000 (UNCONFIRMED), SAE J2012 ({@code P0401}) for UDS. */
    String displayCode();

    DtcStatus status();
}
