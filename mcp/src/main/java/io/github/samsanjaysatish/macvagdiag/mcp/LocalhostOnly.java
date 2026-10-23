package io.github.samsanjaysatish.macvagdiag.mcp;

import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DNS-rebinding defence for the MCP endpoint (the MCP spec requires servers to validate {@code Origin}). Only
 * {@code localhost}, {@code 127.0.0.1} and {@code [::1]} are accepted, with or without a port:
 * <ul>
 *   <li>{@code Host}: required, exactly one, e.g. {@code localhost:8080}.</li>
 *   <li>{@code Origin}: optional (non-browser clients such as Claude Code send none), but if present exactly one,
 *       {@code http} or {@code https}, e.g. {@code http://127.0.0.1:8080}. {@code null} and anything else is
 *       refused.</li>
 * </ul>
 * {@code server} checks the same in front of the servlet; this is defence in depth, so the endpoint stays safe
 * even if it is mounted somewhere without those checks.
 *
 * <p>The SDK's {@code DefaultServerTransportSecurityValidator} isn't used: its wildcard-port patterns are prefix
 * matches ({@code localhost:*} also accepts {@code localhost:evil}), and it ignores repeated headers.
 */
final class LocalhostOnly implements ServerHttpHeaderValidator {

    static final int BAD_ORIGIN_STATUS = 403;
    /** 421 Misdirected Request, as the SDK uses for a bad {@code Host}. */
    static final int BAD_HOST_STATUS = 421;

    private static final String LOOPBACK = "(?:localhost|127\\.0\\.0\\.1|\\[::1\\])";
    private static final Pattern HOST = Pattern.compile(LOOPBACK + "(?::(\\d{1,5}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORIGIN = Pattern.compile("https?://" + LOOPBACK + "(?::(\\d{1,5}))?",
            Pattern.CASE_INSENSITIVE);

    static final LocalhostOnly INSTANCE = new LocalhostOnly();

    private LocalhostOnly() {
    }

    /** Why a request must be refused, or empty if its {@code Host} and {@code Origin} are fine. */
    record Rejection(int status, String message) {
    }

    /**
     * Checks the headers of one request.
     *
     * @param hosts every {@code Host} header value (empty if none)
     * @param origins every {@code Origin} header value (empty if none)
     */
    static Optional<Rejection> check(List<String> hosts, List<String> origins) {
        if (hosts.size() != 1 || !matches(HOST, hosts.getFirst())) {
            return Optional.of(new Rejection(BAD_HOST_STATUS,
                    "Host not allowed: the macvagdiag MCP endpoint only answers on localhost, 127.0.0.1 or [::1]"));
        }
        if (!origins.isEmpty() && (origins.size() != 1 || !matches(ORIGIN, origins.getFirst()))) {
            return Optional.of(new Rejection(BAD_ORIGIN_STATUS,
                    "Origin not allowed: the macvagdiag MCP endpoint only accepts requests from localhost pages"));
        }
        return Optional.empty();
    }

    private static boolean matches(Pattern p, String value) {
        if (value == null) {
            return false;
        }
        Matcher m = p.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return false;
        }
        String port = m.group(1);
        return port == null || (Integer.parseInt(port) >= 1 && Integer.parseInt(port) <= 0xFFFF);
    }

    /** The same check as an SDK hook, so the SDK's transport also refuses on its own. */
    @Override
    public void validate(HeaderAccessor headers) throws ServerTransportSecurityException {
        Optional<Rejection> r = check(headers.getHeader("Host"), headers.getHeader("Origin"));
        if (r.isPresent()) {
            throw new ServerTransportSecurityException(r.get().status(), r.get().message());
        }
    }
}
