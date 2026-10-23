package io.github.samsanjaysatish.macvagdiag.server;

import io.github.samsanjaysatish.macvagdiag.mcp.McpEndpoint;
import io.github.samsanjaysatish.macvagdiag.mcp.McpOptions;
import io.github.samsanjaysatish.macvagdiag.scan.ScanService;
import jakarta.servlet.http.HttpServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <b>WIRING.</b> Mounts the MCP server (Streamable HTTP) at {@link McpEndpoint#PATH} in this process, over the same
 * {@link ScanService} bean as the web API, so the web page and AI clients share one adapter and one request queue
 * (user decision, 2026-10-22). {@link LocalhostOnlyFilter} is on {@code /*}, so it covers {@code /mcp} too; the
 * endpoint repeats the Host/Origin checks itself.
 *
 * <p>Spring destroys beans in reverse dependency order, so the endpoint is closed before the {@link ScanService}.
 */
@Configuration(proxyBeanMethods = false)
class McpHostingConfiguration {

    @Bean(destroyMethod = "close")
    McpEndpoint mcpEndpoint(ScanService scanService, MacVagDiagProperties properties) {
        return McpEndpoint.create(scanService, McpOptions.defaults().withMaskVin(properties.mcpMaskVin()));
    }

    @Bean
    ServletRegistrationBean<HttpServlet> mcpServlet(McpEndpoint endpoint) {
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(endpoint.servlet(), endpoint.path());
        registration.setName("mcp");
        // The SDK's transport answers tool calls as server-sent events.
        registration.setAsyncSupported(true);
        return registration;
    }
}
