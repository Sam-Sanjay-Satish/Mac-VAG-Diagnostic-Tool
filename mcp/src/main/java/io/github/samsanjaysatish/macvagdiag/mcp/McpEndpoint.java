package io.github.samsanjaysatish.macvagdiag.mcp;

import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MCP server for AI agents, hosted in-process by {@code server} over Streamable HTTP (user decision,
 * 2026-10-22), so the web page and the AI share one {@link ScanService} and one adapter.
 *
 * <pre>{@code
 * McpEndpoint mcp = McpEndpoint.create(scanService, McpOptions.defaults());
 * // Spring: new ServletRegistrationBean<>(mcp.servlet(), mcp.path())   (async support on, the default)
 * ...
 * mcp.close();   // before scanService.close()
 * }</pre>
 *
 * <p>Read-only: the tools are exactly {@code get_session_status}, {@code list_modules}, {@code read_fault_codes} and
 * {@code get_vehicle_info} ({@link McpTools}). The servlet refuses any {@code Host} / {@code Origin} that isn't
 * localhost ({@link LocalhostOnly}), as defence in depth behind {@code server}'s own checks.
 *
 * <p>Ownership: the endpoint doesn't own the {@code ScanService}; {@link #close()} closes the MCP sessions only.
 */
public final class McpEndpoint implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);

    /** Where to mount {@link #servlet()}. Clients connect to {@code http://localhost:<port>/mcp}. */
    public static final String PATH = "/mcp";

    static final String SERVER_NAME = "macvagdiag";

    static final String INSTRUCTIONS = """
            macvagdiag is a read-only diagnostic tool for a VW (2013 Jetta TDI), connected to the car through a CAN \
            adapter. It can read the session status, which modules respond, fault codes with status and freeze \
            frame, and the vehicle's identification. It cannot clear codes, run output tests, change coding or \
            adaptation, or send arbitrary requests, and no tool can. Start with get_session_status. Rate limits are \
            enforced by the tool: when a call is refused, follow the message (it says when to retry). Physical \
            conditions (ignition on, engine warm, held rpm, a test drive) need the human: ask the user to set them \
            up and tell you when done. Fault-code descriptions are not available: report codes as they are. \
            "demo": true in a result means the built-in simulated car with made-up data.""";

    private final McpSyncServer server;
    private final McpServlet servlet;
    private final McpTools tools;
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpEndpoint(McpSyncServer server, McpServlet servlet, McpTools tools) {
        this.server = server;
        this.servlet = servlet;
        this.tools = tools;
    }

    /**
     * Builds the MCP server over {@code scan}. Call once at app startup.
     *
     * @param scan the app's one scan service (shared with the web UI); not closed by {@link #close()}
     * @param options user settings (VIN masking)
     */
    public static McpEndpoint create(ScanService scan, McpOptions options) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(options, "options");
        McpTools tools = new McpTools(scan, options);
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(PATH)
                .httpHeaderValidator(LocalhostOnly.INSTANCE)
                .maxRequestSize(McpServlet.MAX_BODY_BYTES)
                .build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, version())
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // McpTools validates every call itself (exact keys, types, known module, then this same input
                // schema) so that rejected calls are audit-logged too; the SDK's own check would answer before
                // our handler runs and leave no audit line.
                .validateToolInputs(false)
                // The servlet transport is blocking: run handlers on the request thread, no extra thread pool.
                .immediateExecution(true)
                .tools(tools.specifications())
                .build();
        return new McpEndpoint(server, new McpServlet(transport, tools, McpJsonDefaults.getMapper()), tools);
    }

    /** The Streamable HTTP servlet (GET / POST / DELETE). Mount it at {@link #path()} with async support on. */
    public HttpServlet servlet() {
        return servlet;
    }

    /** {@value #PATH}. */
    public String path() {
        return PATH;
    }

    /** The tools as {@code tools/list} shows them. */
    List<McpSchema.Tool> tools() {
        return server.listTools();
    }

    McpTools handlers() {
        return tools;
    }

    /** Closes the MCP sessions and the transport's timers. Idempotent. Doesn't close the {@code ScanService}. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            server.closeGracefully();
        } catch (RuntimeException e) {
            log.warn("MCP server didn't close gracefully; closing it now", e);
            server.close();
        }
    }

    private static String version() {
        String v = McpEndpoint.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
