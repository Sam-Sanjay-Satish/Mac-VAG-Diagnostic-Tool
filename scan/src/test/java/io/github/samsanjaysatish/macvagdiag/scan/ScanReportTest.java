package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The text report (VCDS-like layout) and VIN masking, on fixed sample data. */
class ScanReportTest {

    private static final String VIN = SampleScanData.VIN;

    @Test
    @DisplayName("snapshot: header and the engine section, VIN masked")
    void snapshot() {
        String report = ScanReport.text(SampleScanData.autoScan(), true);
        String header = String.join("\n",
                "macvagdiag Auto-Scan (read-only)",
                "=".repeat(78),
                "Date:         2026-01-01 10:00:00 UTC",
                "Duration:     6.0 s",
                "VIN:          SAMPLEVIN00******  (masked)  from module 01",
                "Requested by: web",
                "Modules:      13 checked, 7 responding, 2 with faults, 2 fault code(s)",
                "Discovery:    fixed list of known addresses (gateway installation list not read yet)",
                "",
                "-".repeat(78),
                "Address 01: Engine                         Status: 1 fault",
                "   Protocol:     UDS over ISO-TP (7E0/7E8)",
                "   Part No:      03L906018XX",
                "   Hardware:     03L907309X",
                "   Component:    SAMPLE R4 2.0L TDI",
                "   Software:     9971",
                "   Coding:       0000172A0000",
                "   VIN:          SAMPLEVIN00******",
                "   1 Fault Found:",
                "   P0401-00 - status 2F: testFailed, testFailedThisOperationCycle, pendingDTC, confirmedDTC, "
                        + "testFailedSinceLastClear",
                "              Record: 04 01 00 2F",
                "              Freeze frame (raw, not decoded yet):",
                "                 19 04 04 01 00 FF: 01 01 F4 0D 2A",
                "-".repeat(78),
                "");
        assertTrue(report.startsWith(header), report);
        assertTrue(report.endsWith("End " + "-".repeat(74) + "\n"));
    }

    @Test
    @DisplayName("KWP fault, not-responding module, OK module sections")
    void otherSections() {
        String report = ScanReport.text(SampleScanData.autoScan(), false);
        assertTrue(report.contains("Address 03: ABS Brakes                     Status: 1 fault\n"
                + "   Protocol:     KWP2000 over TP 2.0 (logical address 03)\n"
                + "   1 Fault Found:\n"
                + "   01314 - status 23: symptom 35, static (KWP status layout UNCONFIRMED)\n"
                + "              Record: 05 22 23\n"
                + "              Freeze frame: not available (KWP2000 freeze frames need a request that names the DTC)\n"),
                report);
        assertTrue(report.contains("Address 08: Auto HVAC                      Status: no response (not installed, or not reachable)\n"
                + "   Note:         no response\n"), report);
        assertTrue(report.contains("Address 17: Instruments                    Status: OK\n"
                + "   Protocol:     KWP2000 over TP 2.0 (logical address 17)\n"
                + "   No fault code found.\n"), report);
        assertEquals("Address 19: CAN Gateway                    Status: OK",
                ScanReport.headerLine(report, ModuleAddress.GATEWAY).orElseThrow());
    }

    @Test
    @DisplayName("masking removes the VIN everywhere: header, fields, raw records as text and hex")
    void maskingIsComplete() {
        String masked = ScanReport.text(SampleScanData.autoScan(), true);
        assertFalse(masked.contains(VIN));
        assertFalse(masked.contains("53 41 4D 50 4C 45 56 49 4E"), "VIN bytes as hex");
        String clear = ScanReport.text(SampleScanData.autoScan(), false);
        assertTrue(clear.contains("VIN:          " + VIN + "  from module 01"));

        RawValue raw = SampleScanData.autoScan().maskVin().module(ModuleAddress.ENGINE).orElseThrow()
                .identification().orElseThrow().raw().getFirst();
        assertEquals(VinMask.MASKED_HEX, raw.hex());
        assertEquals("SAMPLEVIN00******", raw.text().orElseThrow());
        assertEquals("WVWZZZ1KZDM******", VinMask.mask("WVWZZZ1KZDM123456"));
        assertEquals("*****", VinMask.mask("SHORT"));
    }

    @Test
    @DisplayName("demo banner, cancelled scans, skipped modules with a limit")
    void demoAndSkipped() {
        AutoScanResult s = SampleScanData.autoScan();
        ModuleResult skipped = ScanEngine.skipped(ModuleAddress.parse("56"), "Auto-Scan stopped: rate limit hit",
                new LimitHit(Limit.REQUESTS_PER_MINUTE, java.time.Duration.ofSeconds(12), "rate limit hit: retry after 12 s"),
                SampleScanData.AT);
        AutoScanResult demo = new AutoScanResult(s.scanId(), s.caller(), s.startedAt(), s.finishedAt(), Optional.empty(),
                Optional.empty(), List.of(skipped), true, true, List.of("Auto-Scan stopped: rate limit hit"));
        String report = ScanReport.text(demo, true);
        assertTrue(report.contains(ScanReport.DEMO_BANNER + "\n"));
        assertTrue(report.contains("VIN:          not read\n"));
        assertTrue(report.contains("Cancelled before the end.\n"));
        assertTrue(report.contains("Address 56: Radio                          Status: skipped\n"
                + "   Note:         Auto-Scan stopped: rate limit hit\n"
                + "   Limit:        rate limit hit: retry after 12 s\n"), report);
    }

    @Test
    @DisplayName("a single module section")
    void moduleSection() {
        String text = ScanReport.text(SampleScanData.moduleResult(ModuleAddress.ENGINE), true);
        assertTrue(text.startsWith("Address 01: Engine"));
        assertFalse(text.contains(VIN));
    }
}
