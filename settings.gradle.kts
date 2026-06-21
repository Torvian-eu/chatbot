// Define global project metadata
val projectVersion = "0.6.0"
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
include("app") // Contains frontend logic for KMP (API clients, ViewModels, UI)
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