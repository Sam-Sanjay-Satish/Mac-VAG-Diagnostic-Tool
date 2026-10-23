plugins {
    id("macvagdiag.java-conventions")
    // FakeDiagnosticService lives in src/testFixtures so scan's tests can use it:
    // testImplementation(testFixtures(project(":diag")))
    `java-test-fixtures`
}

dependencies {
    api(project(":transport"))

    // FakeDiagnosticChannel (applies the real RequestGuard) and FakeTimeSource.
    testImplementation(testFixtures(project(":transport")))
    testImplementation(testFixtures(project(":can-core")))
}
