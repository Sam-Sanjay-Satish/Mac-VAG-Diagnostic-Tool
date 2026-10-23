package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.diag.KwpDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.diag.UdsDiagnosticService;
import io.github.samsanjaysatish.macvagdiag.transport.ApplicationProtocol;

/**
 * The {@code DiagnosticService} operations scan uses, and how many requests each sends at most (from diag's
 * "Requests this layer can produce" table). Budgets reserve this many before each operation, and the wiring's
 * audit listener refuses any request beyond the reservation, so a mismatch fails loudly instead of overspending.
 * {@code FullStackTest} checks the table against what diag really sends.
 */
enum DiagOp {
    START_SESSION("startSession"),
    TESTER_PRESENT("testerPresent"),
    READ_IDENTIFICATION("readIdentification"),
    READ_DTCS("readDtcs"),
    READ_FREEZE_FRAME("readFreezeFrame");

    final String label;

    DiagOp(String label) {
        this.label = label;
    }

    /** The most requests this operation sends under {@code protocol}. */
    int cost(ApplicationProtocol protocol) {
        boolean kwp = protocol == ApplicationProtocol.KWP2000;
        return switch (this) {
            case START_SESSION, TESTER_PRESENT, READ_DTCS -> 1; // 10 89 / 10 01, 3E / 3E 00, 18 02 / 19 02 FF
            case READ_IDENTIFICATION -> kwp ? KwpDiagnosticService.IDENTIFICATION_OPTIONS.size()
                    : UdsDiagnosticService.IDENTIFICATION_DIDS.size();
            case READ_FREEZE_FRAME -> kwp ? 0 : 2; // KWP: NotAvailable, nothing sent; UDS: 19 04 + 19 06
        };
    }
}
