package io.github.samsanjaysatish.macvagdiag.mcp;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A minimal in-test servlet "container": one HTTP exchange through a real {@link HttpServlet} with fake request,
 * response and async context (dynamic proxies), no sockets, no threads. Just enough of the Servlet API for the MCP
 * SDK's Streamable HTTP transport: headers, body, status, writer, {@code startAsync} / {@code complete}.
 *
 * <p>It works synchronously because the SDK's POST handling blocks until the answer (JSON, or a server-sent event
 * on the async context) has been written.
 */
final class FakeHttp {

    /** What came back: status, headers, body; {@code asyncCompleted} if the SSE stream was closed. */
    record Response(int status, Map<String, String> headers, String body, String errorMessage, boolean asyncStarted,
            boolean asyncCompleted) {

        String header(String name) {
            return headers.get(name);
        }

        /** The JSON of every {@code data:} line (server-sent events), or the whole body if it isn't SSE. */
        List<String> messages() {
            if (!body.contains("data: ")) {
                return body.isBlank() ? List.of() : List.of(body);
            }
            List<String> out = new ArrayList<>();
            for (String line : body.split("\n")) {
                if (line.startsWith("data: ")) {
                    out.add(line.substring("data: ".length()));
                }
            }
            return out;
        }
    }

    private FakeHttp() {
    }

    static Response exchange(HttpServlet servlet, String method, Map<String, String> headers, String body)
            throws Exception {
        Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((k, v) -> h.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
        return run(servlet, method, h, body);
    }

    /** With repeated headers, e.g. two {@code Origin}s. */
    static Response exchangeMulti(HttpServlet servlet, String method, Map<String, List<String>> headers, String body)
            throws Exception {
        Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.putAll(headers);
        return run(servlet, method, h, body);
    }

    private static Response run(HttpServlet servlet, String method, Map<String, List<String>> headers,
            String body) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ResponseState res = new ResponseState();
        AsyncState async = new AsyncState();
        HttpServletRequest[] requestRef = new HttpServletRequest[1];
        HttpServletResponse response = proxy(HttpServletResponse.class, (p, m, a) -> res.handle(m, a));
        AsyncContext asyncContext = proxy(AsyncContext.class, (p, m, a) -> async.handle(p, m, a, requestRef[0], response));
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        HttpServletRequest request = proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getMethod" -> method;
            case "getRequestURI" -> McpEndpoint.PATH;
            case "getServletPath" -> McpEndpoint.PATH;
            case "getContextPath", "getPathInfo", "getQueryString" -> m.getName().equals("getContextPath") ? "" : null;
            case "getProtocol" -> "HTTP/1.1";
            case "getDispatcherType" -> DispatcherType.REQUEST;
            case "getHeader" -> {
                List<String> v = headers.get((String) a[0]);
                yield v == null || v.isEmpty() ? null : v.getFirst();
            }
            case "getHeaders" -> Collections.enumeration(headers.getOrDefault((String) a[0], List.of()));
            case "getHeaderNames" -> Collections.enumeration(new ArrayList<>(headers.keySet()));
            case "getContentType" -> {
                List<String> v = headers.get("Content-Type");
                yield v == null ? null : v.getFirst();
            }
            case "getCharacterEncoding" -> null;
            case "getContentLength" -> bytes.length;
            case "getContentLengthLong" -> (long) bytes.length;
            case "getInputStream" -> inputStream(in);
            case "getReader" -> new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            case "startAsync" -> {
                async.started = true;
                yield asyncContext;
            }
            case "isAsyncStarted" -> async.started;
            case "isAsyncSupported" -> true;
            case "getAsyncContext" -> asyncContext;
            default -> defaultValue(m);
        });
        requestRef[0] = request;
        servlet.service(request, response);
        res.writer.flush();
        return new Response(res.status, Map.copyOf(res.headers), res.body.toString(), res.errorMessage, async.started,
                async.completed);
    }

    private static ServletInputStream inputStream(ByteArrayInputStream in) {
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
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class ResponseState {
        int status = 200;
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final StringWriter body = new StringWriter();
        final PrintWriter writer = new PrintWriter(body);
        String errorMessage;

        Object handle(Method m, Object[] a) {
            switch (m.getName()) {
                case "setStatus" -> status = (Integer) a[0];
                case "getStatus" -> {
                    return status;
                }
                case "sendError" -> {
                    status = (Integer) a[0];
                    errorMessage = a.length > 1 ? (String) a[1] : null;
                }
                case "setHeader", "addHeader" -> headers.put((String) a[0], (String) a[1]);
                case "getHeader" -> {
                    return headers.get((String) a[0]);
                }
                case "setContentType" -> headers.put("Content-Type", (String) a[0]);
                case "getContentType" -> {
                    return headers.get("Content-Type");
                }
                case "getWriter" -> {
                    return writer;
                }
                case "getCharacterEncoding" -> {
                    return "UTF-8";
                }
                default -> {
                    return defaultValue(m);
                }
            }
            return null;
        }
    }

    private static final class AsyncState {
        boolean started;
        boolean completed;
        final List<AsyncListener> listeners = new ArrayList<>();

        Object handle(Object self, Method m, Object[] a, HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            switch (m.getName()) {
                case "addListener" -> listeners.add((AsyncListener) a[0]);
                case "complete" -> {
                    if (!completed) {
                        completed = true;
                        for (AsyncListener l : listeners) {
                            l.onComplete(new AsyncEvent((AsyncContext) self, request, response));
                        }
                    }
                }
                case "getRequest" -> {
                    return request;
                }
                case "getResponse" -> {
                    return response;
                }
                default -> {
                    return defaultValue(m);
                }
            }
            return null;
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(FakeHttp.class.getClassLoader(), new Class<?>[] {type},
                (p, m, a) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "fake " + type.getSimpleName();
                    default -> handler.invoke(p, m, a == null ? new Object[0] : a);
                }));
    }

    private static Object defaultValue(Method m) {
        Class<?> t = m.getReturnType();
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        return null;
    }
}
