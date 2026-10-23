plugins {
    id("macvagdiag.java-conventions")
    // FakeScanService, TestScanServices and FakeModuleConnector live in src/testFixtures so the front ends'
    // tests can use them: testImplementation(testFixtures(project(":scan")))
    `java-test-fixtures`
}

dependencies {
    // implementation, not api: front ends (server, mcp) must not even compile against diag or transport.
    // ScanService exposes scan types only (PublicApiTest), so nothing below scan leaks to them.
    implementation(project(":diag"))
    // CanAdapter and TimeSource appear in ScanServiceFactory, which the front ends call at startup.
    api(project(":can-core"))

    // FakeModuleConnector scripts FakeDiagnosticService; FakeTimeSource drives every fake-backed test.
    testFixturesApi(testFixtures(project(":diag")))
    testFixturesApi(testFixtures(project(":can-core")))
    testFixturesImplementation(project(":diag"))

    // The real diag / transport classes for the full-stack tests (RequestGuard, Hex).
    testImplementation(project(":diag"))
    testImplementation(testFixtures(project(":transport")))
}
