package io.github.samsanjaysatish.macvagdiag.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The first filter on <b>every</b> request to this server: Boot registers a {@code Filter} bean on {@code /*}, so it
 * covers the REST API, the static web page, unknown paths, and any servlet mounted later (the MCP endpoint), not
 * just Spring MVC controllers.
 *
 * <ol>
 *   <li><b>Host</b> must be {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, port optional (exactly one Host
 *       header). Stops DNS rebinding: a website whose name now points at 127.0.0.1 still sends its own name.</li>
 *   <li><b>Origin</b>, when sent, must be an {@code http://} localhost origin (same hosts, any port). Rejects
 *       cross-site requests from pages open in the browser, including {@code Origin: null}. There is no CORS
 *       support at all, so a browser never lets another site read a response either.</li>
 *   <li><b>POST / PUT / PATCH</b> must be {@code Content-Type: application/json}, so a plain HTML form on another
 *       site can't start anything ("simple" requests can't carry that type without a CORS preflight).</li>
 * </ol>
 *
 * It also sets headers that keep the page from being framed (clickjacking) or loading anything but its own files.
 */
@Component
final class LocalhostOnlyFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyFilter.class);

    private static final String LOCAL_HOSTS = "(localhost|127\\.0\\.0\\.1|\\[::1\\])";
    static final Pattern ALLOWED_HOST = Pattern.compile(LOCAL_HOSTS + "(:[0-9]{1,5})?", Pattern.CASE_INSENSITIVE);
    static final Pattern ALLOWED_ORIGIN =
            Pattern.compile("http://" + LOCAL_HOSTS + "(:[0-9]{1,5})?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> hosts = Collections.list(request.getHeaders("Host"));
        if (hosts.size() != 1 || !ALLOWED_HOST.matcher(hosts.getFirst().strip()).matches()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN_HOST",
                    "this server only answers requests addressed to localhost or 127.0.0.1", request);
            return;
        }
        for (String origin : Collections.list(request.getHeaders("Origin"))) {
            if (!ALLOWED_ORIGIN.matcher(origin.strip()).matches()) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN_ORIGIN",
                        "cross-origin requests are not allowed; open the page from http://localhost", request);
                return;
            }
        }
        if (BODY_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT)) && !isJson(request.getContentType())) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "send Content-Type: application/json", request);
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** Fixed messages only: nothing from the request is echoed back or logged. */
    private static void reject(HttpServletResponse response, int status, String error, String message,
            HttpServletRequest request) throws IOException {
        log.warn("Rejected {} request: {}", request.getMethod(), error);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
