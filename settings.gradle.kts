plugins {
    // Lets the Java 25 toolchain be downloaded automatically when it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "macvagdiag"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Listed bottom-up: each project may only depend on projects above it in this list.
include(
    "can-core",
    "tools",
    "transport",
    "diag",
    "scan",
    "server",
    "mcp",
)
