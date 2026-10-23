package io.github.samsanjaysatish.macvagdiag.server;

import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.HOST;
import static io.github.samsanjaysatish.macvagdiag.server.LocalRequests.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.samsanjaysatish.macvagdiag.scan.FakeScanService;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@link LocalhostOnlyFilter} (Host, Origin, JSON-only POST, no CORS) and {@link LoopbackOnlyConfiguration}. The
 * filter is checked again on a real Tomcat, including paths outside Spring MVC, in {@link DemoAppTest}.
 */
@WebMvcTest(ScanController.class)
@Import(SwappableScanService.Config.class)
class SecurityTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SwappableScanService service;

    FakeScanService fake;

    @BeforeEach
    void freshFake() {
        fake = service.reset();
    }

    private static final List<String> CORS_HEADERS = List.of("Access-Control-Allow-Origin",
            "Access-Control-Allow-Methods", "Access-Control-Allow-Headers", "Access-Control-Allow-Credentials",
            "Access-Control-Expose-Headers");

    private static void assertNoCors(MockHttpServletResponse r) {
        for (String h : CORS_HEADERS) {
            assertNull(r.getHeader(h), h);
        }
    }

    @ParameterizedTest(name = "Host: {0} → 403")
    @ValueSource(strings = {"evil.example", "evil.example:8080", "localhost.evil.example", "127.0.0.1.nip.io",
            "attacker.localhost", "localhost:", "localhost:80a", "0.0.0.0:8080", "192.168.1.20:8080", "[::2]",
            "localhost:8080, evil.example", ""})
    @DisplayName("a Host that isn't localhost / 127.0.0.1 / [::1] is rejected before the API runs (DNS rebinding)")
    void wrongHost(String host) throws Exception {
        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.get("/api/status").header("Host", host))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN_HOST"))
                .andReturn().getResponse();
        assertNoCors(r);
        assertTrue(fake.calls().isEmpty(), "the service must not be called");
    }

    @Test
    @DisplayName("no Host header at all is rejected")
    void missingHost() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/status")).andExpect(status().isForbidden());
        assertTrue(fake.calls().isEmpty());
    }

    @Test
    @DisplayName("two Host headers are rejected")
    void twoHosts() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/status").header("Host", "localhost", "evil.example"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "Host: {0} → 200")
    @ValueSource(strings = {"localhost", "localhost:8080", "LOCALHOST:8080", "127.0.0.1", "127.0.0.1:65535", "[::1]",
            "[::1]:8080"})
    @DisplayName("localhost hosts, port optional, are allowed")
    void allowedHosts(String host) throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/status").header("Host", host)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "Origin: {0} → 403")
    @ValueSource(strings = {"https://evil.example", "http://evil.example", "null", "http://localhost.evil.example",
            "https://localhost:8080", "file://", "http://127.0.0.1.nip.io:8080", "http://localhost:8080/path"})
    @DisplayName("a cross-origin request is rejected with no CORS headers, GET and POST")
    void crossOrigin(String origin) throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/status").header("Origin", origin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN_ORIGIN"))
                .andReturn().getResponse();
        assertNoCors(r);
        r = mvc.perform(LocalRequests.postJson("/api/scans").header("Origin", origin))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertNoCors(r);
        assertTrue(fake.calls().isEmpty(), "nothing may start");
    }

    @Test
    @DisplayName("a CORS preflight from another site gets no CORS headers")
    void preflight() throws Exception {
        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.options("/api/scans").header("Host", HOST)
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertNoCors(r);
    }

    @Test
    @DisplayName("the page's own origin is allowed, and still gets no CORS headers")
    void sameOriginPost() throws Exception {
        MockHttpServletResponse r = mvc.perform(LocalRequests.postJson("/api/scans").header("Origin", "http://localhost:8080"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertNoCors(r);
        mvc.perform(get("/api/status").header("Origin", "http://127.0.0.1:8080")).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "POST Content-Type: {0} → 415")
    @ValueSource(strings = {"application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/plain",
            "application/jsonx", "application/json-patch+json", "not a type"})
    @DisplayName("a POST that isn't application/json is 415, so an HTML form on another site can't start a scan")
    void postNeedsJson(String contentType) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/scans").header("Host", HOST).header("Content-Type", contentType)
                .content("a=b")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
        assertTrue(fake.calls().isEmpty());
    }

    @Test
    @DisplayName("a POST without a Content-Type is 415")
    void postWithoutContentType() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/scans").header("Host", HOST)).andExpect(status().isUnsupportedMediaType());
        assertTrue(fake.calls().isEmpty());
    }

    @Test
    @DisplayName("application/json with a charset is accepted")
    void jsonWithCharset() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/scans").header("Host", HOST)
                .header("Content-Type", "application/json; charset=utf-8").content("{}")).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("responses can't be framed or sniffed, and the page loads only its own files")
    void securityHeaders() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", LocalhostOnlyFilter.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("isJson: application/json with parameters only")
    void isJson() {
        assertTrue(LocalhostOnlyFilter.isJson(MediaType.APPLICATION_JSON_VALUE));
        assertTrue(LocalhostOnlyFilter.isJson("Application/JSON;charset=UTF-8"));
        assertFalse(LocalhostOnlyFilter.isJson(null));
        assertFalse(LocalhostOnlyFilter.isJson(""));
        assertFalse(LocalhostOnlyFilter.isJson("application/*"));
        assertFalse(LocalhostOnlyFilter.isJson("*/*"));
    }

    // ---- bind address -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("server.address is 127.0.0.1 in application.properties")
    void configuredLoopback() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (var in = SecurityTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        assertEquals("127.0.0.1", p.getProperty("server.address"));
    }

    @Test
    @DisplayName("the bind-address check keeps loopback, fills in 127.0.0.1, and refuses 0.0.0.0 or a LAN address")
    void loopbackCustomizer() throws Exception {
        var customizer = new LoopbackOnlyConfiguration.LoopbackOnlyCustomizer();

        TomcatServletWebServerFactory unset = new TomcatServletWebServerFactory();
        customizer.customize(unset);
        assertEquals("127.0.0.1", unset.getAddress().getHostAddress());

        TomcatServletWebServerFactory ipv6 = new TomcatServletWebServerFactory();
        ipv6.setAddress(InetAddress.getByName("::1"));
        customizer.customize(ipv6);
        assertTrue(ipv6.getAddress().isLoopbackAddress());

        for (String bad : new String[] {"0.0.0.0", "::", "192.168.1.20"}) {
            TomcatServletWebServerFactory f = new TomcatServletWebServerFactory();
            f.setAddress(InetAddress.getByName(bad));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> customizer.customize(f), bad);
            assertTrue(e.getMessage().contains("not a loopback"), e.getMessage());
        }
    }
}
