package io.github.samsanjaysatish.macvagdiag.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The servlet {@code server} mounts at {@link McpEndpoint#PATH}: the SDK's Streamable HTTP transport, behind two
 * checks of our own.
 * <ol>
 *   <li><b>Host / Origin</b> ({@link LocalhostOnly}) on every method, before the SDK sees the request.</li>
 *   <li><b>Unknown tools are audited.</b> The SDK answers a {@code tools/call} for a tool that doesn't exist on its
 *       own, without calling us, so the POST body is read here (bounded to {@link #MAX_BODY_BYTES}) and such calls
 *       are written to the audit log before the request goes on to the SDK. A model asking for
 *       {@code clear_fault_codes} is exactly the kind of call the log must show.</li>
 * </ol>
 * The container must run it with async support on (Spring Boot's {@code ServletRegistrationBean} default): the SDK
 * answers requests as server-sent events on an {@code AsyncContext}.
 */
final class McpServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(McpServlet.class);

    /** Tool calls are tiny; anything bigger is refused before it is parsed. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    // Not serializable, and a servlet is never serialized here; transient keeps -Xlint:serial honest.
    private final transient HttpServletStreamableServerTransportProvider transport;
    private final transient McpTools tools;
    private final transient McpJsonMapper json;

    McpServlet(HttpServletStreamableServerTransportProvider transport, McpTools tools, McpJsonMapper json) {
        this.transport = transport;
        this.tools = tools;
        this.json = json;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        transport.init(config);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Optional<LocalhostOnly.Rejection> rejected = LocalhostOnly.check(headers(request, "Host"),
                headers(request, "Origin"));
        if (rejected.isPresent()) {
            log.warn("MCP request refused: {} (Host {}, Origin {})", rejected.get().message(),
                    Text.quote(request.getHeader("Host")), Text.quote(request.getHeader("Origin")));
            writeError(response, rejected.get().status(), rejected.get().message());
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            transport.service(request, response);
            return;
        }
        byte[] body = readBounded(request);
        if (body == null) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "request body over " + MAX_BODY_BYTES + " bytes");
            return;
        }
        auditUnknownToolCalls(new String(body, charsetOf(request)));
        transport.service(new ReplayedBody(request, body), response);
    }

    /** Writes every {@code tools/call} for a tool that doesn't exist to the audit log. Known tools audit themselves. */
    private void auditUnknownToolCalls(String body) {
        Object message;
        try {
            message = json.readValue(body, Object.class);
        } catch (IOException | RuntimeException e) {
            return; // not JSON: the SDK refuses it, and it isn't a tool call
        }
        List<?> messages = message instanceof List<?> batch ? batch : List.of(message);
        for (Object m : messages) {
            if (m instanceof Map<?, ?> rpc && "tools/call".equals(rpc.get("method"))) {
                Object params = rpc.get("params");
                Object name = params instanceof Map<?, ?> p ? p.get("name") : null;
                if (name instanceof String s && McpTools.NAMES.contains(s)) {
                    continue;
                }
                Map<String, Object> args = new java.util.LinkedHashMap<>();
                if (params instanceof Map<?, ?> p && p.get("arguments") instanceof Map<?, ?> a) {
                    a.forEach((k, v) -> args.put(String.valueOf(k), v));
                }
                tools.audit(McpTools.UNKNOWN_TOOL, McpTools.withName(String.valueOf(name), args), "rejected",
                        "unknown tool " + Text.quote(String.valueOf(name)) + "; nothing was sent to the car");
            }
        }
    }

    private static List<String> headers(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    /** The body, or null if it is over {@link #MAX_BODY_BYTES}. */
    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > MAX_BODY_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Charset charsetOf(HttpServletRequest request) {
        String name = request.getCharacterEncoding();
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Fixed messages only (no request data), so no escaping is needed beyond quotes.
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        response.getWriter().flush();
    }

    /** The request with its already-read body served again to the SDK. */
    private static final class ReplayedBody extends HttpServletRequestWrapper {
        private final byte[] body;

        ReplayedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("non-blocking reads aren't supported on a replayed body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charsetOf(this)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
