package io.github.samsanjaysatish.macvagdiag.scan;

import java.util.List;
import java.util.Optional;

/**
 * The application core, and the only API front ends ({@code server}, {@code mcp}) use. Read-only: nothing here
 * can send a caller-chosen request, clear codes or change anything in the car.
 *
 * <p><b>Thread-safe.</b> The web page and MCP can call it at the same time. Every request to the car goes through
 * one queue and one worker thread that owns the adapter, so requests reach the car one at a time. Pacing, budgets,
 * rate limits, retries with back-off and Tester Present / TP 2.0 keep-alive are all handled inside, whatever the
 * caller does; callers can't trigger or skip any of them.
 *
 * <p>Errors:
 * <ul>
 *   <li>{@link ScanRefusedException}: refused, <b>nothing was sent</b> (adapter missing, a limit with
 *       {@code retryAfter}, module in back-off, another Auto-Scan running, service closed). The message is written
 *       for the user or an AI agent.</li>
 *   <li>What the car did (no answer, negative response, timeout partway) is <b>data</b> in the results, e.g.
 *       {@link ModuleStatus#NOT_RESPONDING}, not an exception.</li>
 *   <li>Bad input can't reach here: a {@link ModuleAddress} only exists for known modules
 *       ({@link ModuleAddress#parse} rejects anything else).</li>
 * </ul>
 *
 * <p>Every request sent, every rejected request and every refusal is audit-logged with the {@link Caller}; MCP
 * adds its tool calls with {@link #recordToolCall}. Create one with {@link ScanServiceFactory}.
 */
public interface ScanService extends AutoCloseable {

    /** The modules this tool knows; only these are accepted. */
    default List<ModuleAddress> knownModules() {
        return ModuleAddress.known();
    }

    /** True when running on the built-in simulated demo car. */
    boolean isDemo();

    /**
     * Adapter connected or not (opens it if needed). Ignition and engine are reported {@code UNKNOWN} with a reason:
     * telling them needs requests that aren't approved (open question). Sends nothing to the car.
     */
    SessionStatus sessionStatus(Caller caller) throws InterruptedException;

    /**
     * Which known modules answer. Probes each one (channel setup and session start, then close), unless the last
     * probe is younger than {@link ScanLimits#moduleListCacheTtl()}; each entry says when it was checked.
     */
    List<ModuleAvailability> listModules(Caller caller) throws InterruptedException;

    /** One module's fault codes with status and freeze frames. No identification is read. */
    ModuleResult readFaultCodes(Caller caller, ModuleAddress module) throws InterruptedException;

    /** The VIN and identification of modules 01, 17 and 19 (UNCONFIRMED which hold the VIN). */
    VehicleInfo vehicleInfo(Caller caller) throws InterruptedException;

    /**
     * Starts an Auto-Scan of every known module and returns at once. Progress goes to {@code listener} (may be null)
     * and to {@link AutoScanJob#events()}.
     *
     * @throws ScanRefusedException if another Auto-Scan is running, or the service is closed
     */
    AutoScanJob startAutoScan(Caller caller, ScanProgressListener listener);

    /** {@link #startAutoScan} and wait for the result. */
    default AutoScanResult autoScan(Caller caller, ScanProgressListener listener) throws InterruptedException {
        return startAutoScan(caller, listener).await();
    }

    /** A recent Auto-Scan (the last 20 are kept) by {@link AutoScanJob#id()}. */
    Optional<AutoScanJob> findAutoScan(String scanId);

    /** Writes an MCP tool call to the audit log (root rule 8). Sends nothing to the car. */
    void recordToolCall(ToolCallRecord call);

    /**
     * Stops: cancels a running scan, interrupts the worker (its session and channel are closed), closes the adapter
     * and the audit log. Calls after this throw {@link ScanRefusedException} ({@code SERVICE_CLOSED}).
     */
    @Override
    void close();
}
