package io.github.samsanjaysatish.macvagdiag.server;

import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.get;
import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.postJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.samsanjaysatish.macvagdiag.scan.SampleScanData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/scans/{id}/report.txt} for {@link SampleScanData}, compared with the snapshot files in
 * {@code src/test/resources/snapshots}. If a snapshot is missing, the actual text is written to
 * {@code build/snapshots/} for review and the test fails; copy it over once it looks right.
 */
@WebMvcTest(ScanController.class)
@Import(SwappableScanService.Config.class)
class ReportExportTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SwappableScanService service;

    @BeforeEach
    void startSampleScan() throws Exception {
        service.reset();
        mvc.perform(postJson("/api/scans")).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("report download matches the snapshot, as a UTF-8 text attachment, with the mileage and no VIN")
    void reportSnapshot() throws Exception {
        String snapshot = "sample-report.txt";
        String fileName = "macvagdiag-scan-sample.txt";
        MvcResult r = mvc.perform(get("/api/scans/scan-sample/report.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + fileName + "\""))
                .andReturn();
        String actual = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(expected(snapshot, actual), actual);
        assertTrue(actual.contains("Mileage:      123450 km (76708 mi), from module 17\n"), actual);
        assertFalse(actual.contains("VIN"), actual);
        assertFalse(actual.contains("DEMO DATA"), "sample data is not demo data");
    }

    private static String expected(String snapshot, String actual) throws IOException {
        try (InputStream in = ReportExportTest.class.getResourceAsStream("/snapshots/" + snapshot)) {
            if (in == null) {
                Path out = Path.of("build", "snapshots", snapshot);
                Files.createDirectories(out.getParent());
                Files.writeString(out, actual, StandardCharsets.UTF_8);
                fail("snapshot " + snapshot + " is missing; actual output written to " + out.toAbsolutePath());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
