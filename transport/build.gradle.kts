plugins {
    id("macvagdiag.java-conventions")
    // FakeDiagnosticChannel lives in src/testFixtures so diag's tests can use it:
    // testImplementation(testFixtures(project(":transport")))
    `java-test-fixtures`
}

dependencies {
    api(project(":can-core"))

    testImplementation(testFixtures(project(":can-core")))
}
