package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.scan.AutoScanJob;
import io.github.samsanjaysatish.macvagdiag.scan.AutoScanResult;
import io.github.samsanjaysatish.macvagdiag.scan.Caller;
import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.github.samsanjaysatish.macvagdiag.scan.ScanReport;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST API: a thin JSON front end over {@link ScanService}, every call as {@link Caller#web()}. It offers what
 * the service offers and nothing more: <b>no endpoint sends a caller-chosen request or bytes, and none clears or
 * writes anything</b> ({@code EndpointSetTest} pins the exact list).
 *
 * <p>Errors ({@link ApiErrorHandler}): adapter not connected 503, rate limit or module back-off 429 with
 * {@code Retry-After}, Auto-Scan already running 409, malformed or unknown module 400, unknown scan 404. A module
 * that doesn't answer is a result, not an error: 200 with {@code "status": "NOT_RESPONDING"}.
 */
@RestController
@RequestMapping("/api")
final class ScanController {

    static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final ScanService scan;

    ScanController(ScanService scan) {
        this.scan = Objects.requireNonNull(scan, "scan");
    }

    /** Adapter, ignition and engine state. Sends nothing to the car. */
    @GetMapping("/status")
    ApiJson.Status status() throws InterruptedException {
        return ApiJson.status(scan.sessionStatus(Caller.web()));
    }

    /** Which known modules answer (cached by scan for a minute). */
    @GetMapping("/modules")
    ApiJson.Modules modules() throws InterruptedException {
        return ApiJson.modules(scan.listModules(Caller.web()));
    }

    /** One module's fault codes. {@code address}: two hex digits naming a known module, e.g. {@code 17}. */
    @GetMapping("/modules/{address}/faults")
    ApiJson.ModuleReport faults(@PathVariable String address) throws InterruptedException {
        ModuleAddress module = ModuleAddress.parse(address); // InvalidModuleAddressException → 400, nothing sent
        return ApiJson.module(scan.readFaultCodes(Caller.web(), module));
    }

    /** Starts an Auto-Scan. Takes no parameters: the body must be empty or {@code {}}. */
    @PostMapping(path = "/scans", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiJson.Started> startScan(@RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "POST /api/scans takes no parameters; send {} or an empty body");
        }
        AutoScanJob job = scan.startAutoScan(Caller.web(), null);
        String href = "/api/scans/" + job.id();
        return ResponseEntity.accepted().location(URI.create(href))
                .body(new ApiJson.Started(job.id(), href, href + "/report.txt"));
    }

    /** Progress and, once finished, the results. */
    @GetMapping("/scans/{id}")
    ApiJson.ScanJob scanJob(@PathVariable String id) {
        return ApiJson.job(find(id), scan.isDemo());
    }

    /** The text report (VCDS-like layout) as a download; {@code maskVin=true} masks the VIN everywhere. */
    @GetMapping("/scans/{id}/report.txt")
    ResponseEntity<String> report(@PathVariable String id, @RequestParam(defaultValue = "false") boolean maskVin) {
        AutoScanJob job = find(id);
        if (!job.isDone()) {
            throw new ApiException(HttpStatus.CONFLICT, "SCAN_NOT_FINISHED",
                    "the Auto-Scan is still running; download the report when it has finished");
        }
        AutoScanResult result = job.result().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SCAN_FAILED",
                "the Auto-Scan failed, so there is no report; see GET /api/scans/" + job.id()));
        String file = "macvagdiag-" + safeName(job.id()) + (result.demo() ? "-demo" : "")
                + (maskVin ? "-vin-masked" : "") + ".txt";
        return ResponseEntity.ok().contentType(TEXT_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(ScanReport.text(result, maskVin));
    }

    private AutoScanJob find(String id) {
        return scan.findAutoScan(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCAN_NOT_FOUND",
                "no recent Auto-Scan with that ID (the last 20 are kept; they are lost when the app restarts)"));
    }

    private static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9-]", "_");
    }
}
