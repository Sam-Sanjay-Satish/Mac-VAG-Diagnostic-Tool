plugins {
    id("macvagdiag.java-conventions")
    // FakeTimeSource lives in src/testFixtures so other projects' tests can use it:
    // testImplementation(testFixtures(project(":can-core")))
    `java-test-fixtures`
}

dependencies {
    implementation(libs.jserialcomm)
}
