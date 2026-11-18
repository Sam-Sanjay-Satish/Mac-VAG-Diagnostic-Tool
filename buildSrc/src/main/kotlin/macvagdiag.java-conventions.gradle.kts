// Shared settings for every project: Java 25, SLF4J, JUnit, strict compiler warnings.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("slf4j-api").get())

    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform {
        // Tests tagged "hardware" need a real adapter and car; they never run in a normal build or CI.
        excludeTags("hardware")
    }
    // A test stuck waiting for a frame fails fast instead of hanging the build.
    // SEPARATE_THREAD makes the timeout work even when the stuck call ignores interrupts.
    systemProperty("junit.jupiter.execution.timeout.default", "10 s")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
}

// One CANable, one serial port: hardware tasks of different projects never run at the same time.
val canAdapter = gradle.sharedServices.registerIfAbsent("canAdapter", CanAdapterLock::class) {
    maxParallelUsages = 1
}

// Manual only, on the Mac with the CANable plugged into the car: ./gradlew :<project>:hardwareTest
val hardwareTest by tasks.registering(Test::class) {
    description = "Runs tests tagged 'hardware' against the real adapter and car."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("hardware")
    }
    // Most projects have no hardware tests; that's not an error.
    failOnNoDiscoveredTests = false
    // Results depend on the car, not the code, so never reuse a previous run.
    outputs.upToDateWhen { false }
    usesService(canAdapter)
    // Show each test and its output live, e.g. "start a VCDS Auto-Scan now" while a test waits for traffic.
    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
