package io.github.samsanjaysatish.macvagdiag.scan;

import java.time.Instant;
import java.util.Objects;

/** Auto-Scan progress, for the web page ("scanning 08 / Auto HVAC...") and MCP progress notifications. */
public sealed interface ScanEvent {

    String scanId();

    Instant at();

    /** One readable line. */
    String message();

    record Started(String scanId, int totalModules, Instant at) implements ScanEvent {
        public Started {
            Objects.requireNonNull(scanId, "scanId");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public String message() {
            return "Auto-Scan started: " + totalModules + " modules to check";
        }
    }

    record ModuleStarted(String scanId, int index, int total, ModuleAddress module, Instant at) implements ScanEvent {
        public ModuleStarted {
            Objects.requireNonNull(scanId, "scanId");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public String message() {
            return String.format("scanning %s / %s... (%d of %d)", module.hex(), module.name(), index, total);
        }
    }

    record ModuleFinished(String scanId, int index, int total, ModuleResult result, Instant at) implements ScanEvent {
        public ModuleFinished {
            Objects.requireNonNull(scanId, "scanId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public String message() {
            ModuleResult r = result;
            String what = r.status() == ModuleStatus.FAULTS ? r.faults().size() + " fault code(s)" : r.status().description();
            return String.format("%s / %s: %s (%d of %d)", r.module().hex(), r.module().name(), what, index, total);
        }
    }

    record Finished(String scanId, AutoScanResult result, Instant at) implements ScanEvent {
        public Finished {
            Objects.requireNonNull(scanId, "scanId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public String message() {
            return String.format("Auto-Scan %s: %d module(s) responding, %d fault code(s)",
                    result.cancelled() ? "cancelled" : "finished", result.respondingModules().size(), result.faultCount());
        }
    }

    record Failed(String scanId, String reason, Instant at) implements ScanEvent {
        public Failed {
            Objects.requireNonNull(scanId, "scanId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public String message() {
            return "Auto-Scan failed: " + reason;
        }
    }
}
