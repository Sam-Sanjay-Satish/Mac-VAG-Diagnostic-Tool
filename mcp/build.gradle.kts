plugins {
    id("macvagdiag.java-conventions")
}

dependencies {
    // api: McpEndpoint.create takes a ScanService.
    api(project(":scan"))

    // Official MCP Java SDK (mcp-core + mcp-json-jackson3). Approved by the user on 2026-10-22.
    // implementation: no SDK type appears in mcp's public API (McpEndpoint, McpOptions).
    implementation(platform(libs.mcp.bom))
    implementation(libs.mcp)

    // The SDK's Streamable HTTP transport is a jakarta servlet. server's embedded Tomcat provides the API at
    // runtime (approved 2026-10-22); McpEndpoint.servlet() returns an HttpServlet.
    compileOnly(libs.jakarta.servlet.api)

    // FakeScanService, SampleScanData, TestScanServices, FakeTimeSource.
    testImplementation(testFixtures(project(":scan")))
    // The in-test servlet harness drives the real transport without a container.
    testImplementation(libs.jakarta.servlet.api)
}
