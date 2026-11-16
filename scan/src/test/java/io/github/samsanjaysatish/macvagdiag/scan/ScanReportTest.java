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
                "Modules:      16 checked, 13 responding, 3 with faults, 3 fault code(s)",
                "Discovery:    the gateway's installation list",
                "Note:         module list: from the gateway's installation list (16 installed)",
                "",
                "Installed modules (gateway status):",
                "   01-Engine                       Status: Malfunction 0010",
                "   02-Transmission                 Status: OK 0000",
                "   03-ABS Brakes                   Status: Malfunction 0010",
                "   04-Steering Angle               Status: OK 0000",
                "   05-Access/Start Authorization   Status: OK 0000",
                "   08-Auto HVAC                    Status: OK 0000",
                "   09-Central Electrics            Status: OK 0000",
                "   15-Airbags                      Status: OK 0000",
                "   17-Instruments                  Status: Malfunction 0010",
                "   19-CAN Gateway                  Status: OK 0000",
                "   25-Immobilizer                  Status: OK 0000",
                "   2B-Steering Column Lock         Status: OK 0000",
                "   2E-Media Player                 Status: Cannot be reached 1100",
                "   44-Steering Assist              Status: OK 0000",
                "   56-Radio                        Status: Cannot be reached 1100",
                "   77-Telephone                    Status: OK 0000",
                "",
                "-".repeat(78),
                "Address 01: Engine                         Status: 1 fault",
                "   Protocol:     KWP2000 over TP 2.0 (logical address 01)",
                "   Gateway:      Malfunction 0010",
                "   Part No:      03L906018XX",
                "   Component:    SAMPLE R4 2.0L TDI",
                "   Software:     9971",
                "   Coding:       0001234",
                "   VIN:          SAMPLEVIN00******",
                "   1 Fault Found:",
                "   001025 (P0401) - status E2 (11100010): type 002, MIL ON",
                "              Record: 04 01 E2",
                "              Freeze frame (decoded fields, then the raw records):",
                "                 MILEAGE: 12345 km",
                "                 12 00 04 04 01: 6C 01 E2 02 01 FF 00 30 39 00 01 FF BF 20 B1 04 04 01",
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
                + "   Gateway:      Malfunction 0010\n"
                + "   1 Fault Found:\n"
                + "   01314 - status 23 (00100011): type 003, Intermittent\n"
                + "              Record: 05 22 23\n"
                + "              Freeze frame: not available (sample: no freeze frame stored)\n"),
                report);
        assertTrue(report.contains("Address 04: Steering Angle                 Status: OK 0000 (from the gateway)\n"
                + "   Gateway:      OK 0000\n"
                + "   Note:         04 has no diagnostic link of its own"), report);
        assertTrue(report.contains("Address 17: Instruments                    Status: 1 fault\n"
                + "   Protocol:     UDS over ISO-TP (714/77E)\n"
                + "   Gateway:      Malfunction 0010\n"
                + "   1 Fault Found:\n"
                + "   U0115-00 - status 08 (00001000): Intermittent - Confirmed - Tested Since Memory Clear\n"), report);
        assertTrue(report.contains("Address 56: Radio                          Status: no response (gateway: Cannot be "
                + "reached 1100)\n"
                + "   Gateway:      Cannot be reached 1100\n"
                + "   Note:         no response\n"), report);
        assertTrue(report.contains("Address 08: Auto HVAC                      Status: OK\n"
                + "   Protocol:     KWP2000 over TP 2.0 (logical address 2C)\n"
                + "   Gateway:      OK 0000\n"
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
        assertTrue(report.contains("Discovery:    fixed list of known modules (the gateway's installation list was not "
                + "read)\n"), report);
        assertFalse(report.contains("Installed modules (gateway status)"), report);
    }

    @Test
    @DisplayName("a single module section")
    void moduleSection() {
        String text = ScanReport.text(SampleScanData.moduleResult(ModuleAddress.ENGINE), true);
        assertTrue(text.startsWith("Address 01: Engine"));
        assertFalse(text.contains(VIN));
    }
}
