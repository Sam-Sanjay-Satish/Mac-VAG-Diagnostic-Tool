plugins {
    id("macvagdiag.java-conventions")
    // Spring Boot approved by the user on 2026-10-22. Versions come from the Boot BOM (platform below);
    // the io.spring.dependency-management plugin is deliberately not used.
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":scan"))
    // MCP over Streamable HTTP at /mcp, in this process (user decision 2026-10-22), sharing the one ScanService.
    implementation(project(":mcp"))

    implementation(platform(libs.spring.boot.bom))
    // Boot 4 name of the old spring-boot-starter-web: Spring MVC, embedded Tomcat, Jackson, default Logback.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // @WebMvcTest / MockMvc slice support (a separate module in Boot 4).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // FakeScanService, SampleScanData, FakeTimeSource.
    testImplementation(testFixtures(project(":scan")))
}

springBoot {
    mainClass = "io.github.samsanjaysatish.macvagdiag.server.MacVagDiagApplication"
}
