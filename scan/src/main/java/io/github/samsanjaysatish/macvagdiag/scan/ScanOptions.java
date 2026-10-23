package io.github.samsanjaysatish.macvagdiag.scan;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * App-startup settings for {@link ScanServiceFactory}.
 *
 * @param auditLog the append-only JSON-lines audit log; default {@link #defaultAuditLogPath()}
 * @param bitsPerSecond the diagnostic CAN bus speed the adapter is opened at
 * @param limits pacing, retries and budgets (clamped to hard bounds, see {@link ScanLimits})
 */
public record ScanOptions(Path auditLog, int bitsPerSecond, ScanLimits limits) {

    /** UNCONFIRMED: the Jetta's diagnostic CAN (OBD pins 6/14) is expected at 500 kbit/s. */
    public static final int DEFAULT_BITS_PER_SECOND = 500_000;

    static final String APP_DIRECTORY = "macvagdiag";
    static final String AUDIT_FILE = "audit.jsonl";

    public ScanOptions {
        Objects.requireNonNull(auditLog, "auditLog");
        Objects.requireNonNull(limits, "limits");
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("bitsPerSecond must be positive: " + bitsPerSecond);
        }
    }

    /** Default audit-log path, 500 kbit/s, {@link ScanLimits#DEFAULTS}. */
    public static ScanOptions defaults() {
        return new ScanOptions(defaultAuditLogPath(), DEFAULT_BITS_PER_SECOND, ScanLimits.DEFAULTS);
    }

    public ScanOptions withAuditLog(Path path) {
        return new ScanOptions(path, bitsPerSecond, limits);
    }

    public ScanOptions withBitsPerSecond(int value) {
        return new ScanOptions(auditLog, value, limits);
    }

    public ScanOptions withLimits(ScanLimits value) {
        return new ScanOptions(auditLog, bitsPerSecond, value);
    }

    /**
     * {@code ~/Library/Application Support/macvagdiag/audit.jsonl} on macOS; elsewhere
     * {@code $XDG_DATA_HOME/macvagdiag/audit.jsonl} (default {@code ~/.local/share}). Never inside the repo.
     */
    public static Path defaultAuditLogPath() {
        return defaultAuditLogPath(System.getProperty("os.name", ""), System.getProperty("user.home", "."),
                System.getenv("XDG_DATA_HOME"));
    }

    static Path defaultAuditLogPath(String osName, String userHome, String xdgDataHome) {
        Path home = Path.of(userHome);
        Path base;
        if (osName.toLowerCase(Locale.ROOT).startsWith("mac")) {
            base = home.resolve("Library").resolve("Application Support");
        } else if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            base = Path.of(xdgDataHome);
        } else {
            base = home.resolve(".local").resolve("share");
        }
        return base.resolve(APP_DIRECTORY).resolve(AUDIT_FILE);
    }
}
