// Define global project metadata
val projectName = "chatbot"
val projectGroup = "eu.torvian"
val defaultVersion = "1.0-SNAPSHOT"

// Set the root project's name
rootProject.name = projectName

/**
 * Dynamically configure each project's group and version.
 * - Group: Defined globally
 * - Version: Falls back to default if not defined in build.gradle.kts
 */
gradle.beforeProject {
    group = projectGroup

    // Fallback to default version if not specified in the module
    if (project.version == "unspecified") {
        version = defaultVersion
    }

    // Log for verification
    println("Configured project: $name → Version: $version")
}

// Include the sub-modules
include("common") // Contains shared code (models)
include("server") // Contains backend logic (services, data, external, server API)
include("app") // Contains frontend logic for KMP (API clients, ViewModels, UI)

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