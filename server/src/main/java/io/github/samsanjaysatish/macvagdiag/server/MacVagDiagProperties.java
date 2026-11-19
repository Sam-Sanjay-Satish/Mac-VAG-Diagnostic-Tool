package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.scan.ScanOptions;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings, prefix {@code macvagdiag} (command line {@code --macvagdiag.port=...}, environment
 * {@code MACVAGDIAG_PORT=...}, or {@code application.properties}).
 *
 * @param demo run on the built-in simulated demo car instead of the CANable; {@code --demo} does the same
 * @param port the CANable's serial port, e.g. {@code /dev/cu.usbmodem14101}; empty = auto-detect (USB ID
 *     {@code 16D0:117E} on {@code /dev/cu.usbmodem*}, re-checked until one is plugged in)
 * @param auditLog the append-only audit log; empty = {@link ScanOptions#defaultAuditLogPath()}
 *     ({@code ~/Library/Application Support/macvagdiag/audit.jsonl} on macOS)
 * @param bitsPerSecond the diagnostic CAN bus speed; 0 = {@link ScanOptions#DEFAULT_BITS_PER_SECOND} (500 kbit/s,
 *     UNCONFIRMED until the sniffing session)
 * @param labels label-file folders for live-data names and specs, comma-separated, searched in order (e.g. a
 *     user folder first); empty = no labels (values keep raw IDs and the module's generic quantity and unit).
 *     The files stay on this Mac; field names and spec ranges go to the AI client in tool results. Demo mode
 *     uses its own made-up labels.
 */
@ConfigurationProperties("macvagdiag")
public record MacVagDiagProperties(boolean demo, String port, String auditLog, int bitsPerSecond, String labels) {

    /** The scan settings these properties describe; scan's own defaults for anything not set. */
    ScanOptions scanOptions() {
        ScanOptions options = ScanOptions.defaults();
        if (auditLog != null && !auditLog.isBlank()) {
            options = options.withAuditLog(Path.of(auditLog));
        }
        if (bitsPerSecond > 0) {
            options = options.withBitsPerSecond(bitsPerSecond);
        }
        if (labels != null && !labels.isBlank()) {
            options = options.withLabelFolders(java.util.Arrays.stream(labels.split(","))
                    .map(String::strip).filter(x -> !x.isEmpty()).map(Path::of).toList());
        }
        return options;
    }

    /** The configured port, or null to auto-detect. */
    String configuredPort() {
        return port == null || port.isBlank() ? null : port.strip();
    }
}
