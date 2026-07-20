// Define global project metadata
val projectVersion = "0.9.0-SNAPSHOT"
val projectName = "chatbot"
val projectGroup = "eu.torvian"

// Set the root project's name
rootProject.name = projectName

/**
 * Dynamically configure each project's group and version.
 * - Group: Defined globally
 * - Version: Set from central projectVersion
 */
gradle.beforeProject {
    group = projectGroup
    version = projectVersion

    // Expose version to all projects via extra properties
    extensions.extraProperties["centralVersion"] = projectVersion

    // Log for verification
    println("Configured project: $name → Version: $version")
}

// Include the sub-modules
include("common") // Contains shared code (models)
include("server") // Contains backend logic (services, data, external, server API)
include("app") // Contains shared frontend logic (KMP library: API clients, ViewModels, UI)
include("androidApp") // Contains the Android application entry point
include("desktopApp") // Contains the Desktop (JVM) application entry point
include("webApp") // Contains the Web (Wasm/JS) application entry point
include("worker") // Contains standalone worker service logic

// Include the custom build logic
includeBuild("build-logic")

// Configure plugin management
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// Apply Foojay toolchain resolver to enable automatic JDK downloading
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
