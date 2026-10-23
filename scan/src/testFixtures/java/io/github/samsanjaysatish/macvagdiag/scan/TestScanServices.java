package io.github.samsanjaysatish.macvagdiag.scan;

import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A <b>real</b> {@link ScanService} (queue, sessions, limits, audit log, all of it) on a
 * {@link FakeModuleConnector} and a {@link FakeTimeSource}, with an in-memory or temp-file audit log. Script the
 * car through {@link #connector()}; anything unscripted doesn't answer.
 *
 * <pre>{@code
 * try (TestScanServices t = TestScanServices.create()) {
 *     t.connector().kwp("17", () -> FakeDiagnosticService.kwp().onStartSession(...).onReadDtcs(...));
 *     ModuleResult r = t.service().readFaultCodes(Caller.web(), ModuleAddress.parse("17"));
 *     assertTrue(t.auditLines().stream().anyMatch(l -> l.contains("\"event\":\"result\"")));
 * }
 * }</pre>
 *
 * For front-end tests that don't care about the car, {@link FakeScanService} is simpler, and
 * {@code ScanServiceFactory.demo(FakeTimeSource.autoAdvancing(), options)} gives the whole real stack on the demo
 * car.
 */
public final class TestScanServices implements AutoCloseable {

    private final FakeTimeSource time;
    private final FakeModuleConnector connector;
    private final StringWriter memory;
    private final Path auditFile;
    private final DefaultScanService service;

    private TestScanServices(FakeTimeSource time, ScanLimits limits, Path auditFile, boolean demo) {
        this.time = time;
        this.connector = new FakeModuleConnector(time);
        this.auditFile = auditFile;
        AuditLog audit;
        if (auditFile == null) {
            memory = new StringWriter();
            audit = AuditLog.to(memory, time, demo);
        } else {
            memory = null;
            audit = AuditLog.append(auditFile, time, demo);
        }
        this.service = new DefaultScanService(connector, time, limits, audit, demo);
    }

    /** Auto-advancing fake clock, default limits, in-memory audit log. */
    public static TestScanServices create() {
        return create(FakeTimeSource.autoAdvancing(), ScanLimits.DEFAULTS);
    }

    public static TestScanServices create(FakeTimeSource time, ScanLimits limits) {
        return new TestScanServices(time, limits, null, false);
    }

    /** With the audit log appended to {@code auditFile}. */
    public static TestScanServices create(FakeTimeSource time, ScanLimits limits, Path auditFile) {
        return new TestScanServices(time, limits, auditFile, false);
    }

    public ScanService service() {
        return service;
    }

    public FakeModuleConnector connector() {
        return connector;
    }

    public FakeTimeSource time() {
        return time;
    }

    /** Every audit-log line so far. */
    public List<String> auditLines() {
        String text;
        if (memory != null) {
            text = memory.toString();
        } else {
            try {
                text = Files.exists(auditFile) ? Files.readString(auditFile, StandardCharsets.UTF_8) : "";
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Arrays.stream(text.split("\n")).filter(l -> !l.isEmpty()).toList();
    }

    /** Audit lines with {@code "event":"<event>"}. */
    public List<String> auditLines(String event) {
        return auditLines().stream().filter(l -> l.contains("\"event\":\"" + event + "\"")).toList();
    }

    @Override
    public void close() {
        service.close();
    }
}
