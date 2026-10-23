plugins {
    id("macvagdiag.java-conventions")
    application
}

dependencies {
    implementation(project(":can-core"))
    // Sends can-core's SLF4J logs to stderr (configured in simplelogger.properties).
    runtimeOnly(libs.slf4j.simple)
    // FakeTimeSource
    testImplementation(testFixtures(project(":can-core")))
}

application {
    // One entry point with subcommands: can-log, list-ports, capture-info.
    mainClass = "io.github.samsanjaysatish.macvagdiag.tools.Main"
    applicationName = "macvagdiag-tools"
    // jSerialComm loads a native library; without this Java 25 prints a restricted-method warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    // So the default output path local/captures/... lands in the repo's git-ignored local/ directory.
    workingDir = rootDir
}
