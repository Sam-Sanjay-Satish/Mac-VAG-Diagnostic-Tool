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
    READ_FREEZE_FRAME("readFreezeFrame"),
    READ_INSTALLATION_LIST("readInstallationList");

    final String label;

    DiagOp(String label) {
        this.label = label;
    }

    /** The most requests this operation sends under {@code protocol}. */
    int cost(ApplicationProtocol protocol) {
        boolean kwp = protocol == ApplicationProtocol.KWP2000;
        return switch (this) {
            // 10 89 / 10 03, 3E / 3E 00, 18 0x FF 00 / 19 02 AE, 22 04 A1
            case START_SESSION, TESTER_PRESENT, READ_DTCS, READ_INSTALLATION_LIST -> 1;
            case READ_IDENTIFICATION -> kwp ? KwpDiagnosticService.MAX_IDENTIFICATION_REQUESTS
                    : UdsDiagnosticService.IDENTIFICATION_DIDS.size();
            case READ_FREEZE_FRAME -> kwp ? KwpDiagnosticService.FREEZE_FRAME_REQUESTS // 12 00 04 hi lo
                    : UdsDiagnosticService.FREEZE_FRAME_REQUESTS; // 19 04 + 19 06
        };
    }
}
