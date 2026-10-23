package io.github.samsanjaysatish.macvagdiag.server;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MockMvc requests as the browser on this machine sends them: {@code Host: localhost:8080}. MockMvc adds no Host
 * header by itself, and {@link LocalhostOnlyFilter} rejects a request without one.
 */
final class LocalRequests {

    static final String HOST = "localhost:8080";

    private LocalRequests() {
    }

    static MockHttpServletRequestBuilder get(String path) {
        return MockMvcRequestBuilders.get(path).header("Host", HOST);
    }

    /** {@code POST} with {@code Content-Type: application/json} and body {@code {}}, like the web page. */
    static MockHttpServletRequestBuilder postJson(String path) {
        return MockMvcRequestBuilders.post(path).header("Host", HOST).contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }
}
