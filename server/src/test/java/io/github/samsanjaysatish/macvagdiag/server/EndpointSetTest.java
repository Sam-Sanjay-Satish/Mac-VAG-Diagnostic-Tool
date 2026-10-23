package io.github.samsanjaysatish.macvagdiag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The set of HTTP endpoints of the whole app is exactly this list. Adding one (say, a raw-request, clear or write
 * endpoint) fails here and has to be added on purpose. Only read endpoints plus the one POST that starts an Auto-Scan.
 */
@SpringBootTest(properties = "macvagdiag.demo=true")
class EndpointSetTest {

    static final Path AUDIT = TestAuditLogs.newFile("endpoints");

    @DynamicPropertySource
    static void auditLog(DynamicPropertyRegistry registry) {
        registry.add("macvagdiag.audit-log", AUDIT::toString);
    }

    static final Set<String> EXPECTED = Set.of(
            "GET /api/status",
            "GET /api/modules",
            "GET /api/modules/{address}/faults",
            "POST /api/scans",
            "GET /api/scans/{id}",
            "GET /api/scans/{id}/report.txt");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("the app's endpoints are exactly the six of the guide; everything else mapped is Boot's /error")
    void endpointSet() {
        Set<String> ours = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Class<?> type = e.getValue().getBeanType();
            if (type.getPackageName().startsWith("io.github.samsanjaysatish.macvagdiag")) {
                assertEquals(1, info.getMethodsCondition().getMethods().size(), "one HTTP method each: " + info);
                for (String pattern : info.getPatternValues()) {
                    ours.add(info.getMethodsCondition().getMethods().iterator().next() + " " + pattern);
                }
            } else {
                assertEquals("BasicErrorController", type.getSimpleName(), "unexpected handler " + info);
                assertEquals(Set.of("/error"), info.getPatternValues());
            }
        }
        assertEquals(new TreeSet<>(EXPECTED), ours);
    }

    @Test
    @DisplayName("ScanController is the app's only controller")
    void oneController() {
        Set<String> controllers = new TreeSet<>();
        for (String name : context.getBeanNamesForAnnotation(Controller.class)) {
            controllers.add(context.getType(name).getSimpleName());
        }
        for (String name : context.getBeanNamesForAnnotation(RestController.class)) {
            controllers.add(context.getType(name).getSimpleName());
        }
        controllers.remove("BasicErrorController");
        assertEquals(Set.of("ScanController"), controllers);
    }

    @Test
    @DisplayName("no endpoint path names a write, clear, raw or arbitrary-request operation")
    void noWriteSoundingPaths() {
        for (String endpoint : EXPECTED) {
            String lower = endpoint.toLowerCase(java.util.Locale.ROOT);
            for (String word : new String[] {"clear", "write", "raw", "send", "request", "reset", "coding", "adapt",
                    "output", "routine", "security", "flash", "delete"}) {
                assertTrue(!lower.contains(word), endpoint + " contains " + word);
            }
        }
    }
}
