import java.io.File
import java.util.Properties

// ==========================================================
// Global Project Metadata
// ==========================================================

val projectVersion = "0.11.0-SNAPSHOT"
val projectName = "chatbot"
val projectGroup = "eu.torvian"

// Set the root project's name
rootProject.name = projectName

// ==========================================================
// Optional External Build Artifact Configuration
// ==========================================================
//
// Goal:
// Allow certain environments (such as Docker or a specific local clone)
// to store Gradle build outputs outside the project directory.
//
// This helps avoid:
// - lock conflicts between Windows Gradle and Linux Gradle
// - build artifact pollution inside the repository
// - slower file searches due to generated files
//
// Precedence:
// 1. Environment variable: CHATBOT_GRADLE_ROOT
// 2. Gitignored file:      local.gradle.properties
// 3. Otherwise:            default Gradle behavior
//
// Supported path styles:
// - Absolute path:
//     C:/temp/chatbot-gradle/windows
//     /app/workspace/gradle-chatbot/docker
//
// - Relative path (resolved relative to the project root):
//     ../gradle-chatbot/windows
//     ../gradle-chatbot/docker
//
// If no external root is configured, Gradle will use the default build/
// directory inside each module.
//
// If an external root is configured, this main build uses a dedicated namespace:
//   <externalRoot>/main/...
//
// This keeps it clearly separated from other included builds such as:
//   <externalRoot>/build-logic/...
//
// Note:
// The Gradle project cache directory (normally .gradle/) is NOT configured here.
// That is handled externally by the Gradle wrapper scripts / command line via:
//   --project-cache-dir
//   --no-watch-fs
// ==========================================================

// Optional local per-clone configuration file.
// This file should be gitignored, so each clone/user can choose its own setup.
val localGradlePropertiesFile = File(rootDir, "local.gradle.properties")

// Reads 'chatbot.gradle.root' from local.gradle.properties, if present.
val localGradleRoot: String? =
    if (localGradlePropertiesFile.isFile) {
        Properties().run {
            localGradlePropertiesFile.inputStream().use { load(it) }
            getProperty("chatbot.gradle.root")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    } else {
        null
    }

// Environment variable takes precedence over local.gradle.properties.
// This is useful for Docker, CI, or temporary overrides.
val gradleRootRaw: String? =
    System.getenv("CHATBOT_GRADLE_ROOT")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: localGradleRoot

// Resolve the configured path, if any.
// - Absolute paths are used as-is
// - Relative paths are resolved relative to the project root
//
// canonicalFile is used to normalize the final path, resolving things like:
// - ".."
// - "."
val configuredExternalGradleRoot: File? =
    gradleRootRaw?.let { rawPath ->
        val configuredPath = File(rawPath)
        val resolvedPath =
            if (configuredPath.isAbsolute) {
                configuredPath
            } else {
                File(rootDir, rawPath)
            }

        resolvedPath.canonicalFile
    }

// Use a dedicated namespace for the main build.
val externalGradleRoot: File? =
    configuredExternalGradleRoot?.let { File(it, "main") }

// ==========================================================
// Per-Project Configuration
// ==========================================================
//
// This block runs for every project/module in the build.
// It sets:
// - group
// - version
// - shared version extra property
//
// Additionally, if an external root is configured,
// it redirects each project's build directory from:
//   <module>/build
// to:
//   <externalGradleRoot>/build/<projectPath>
//
// Examples:
//   root project   -> <externalGradleRoot>/build/root
//   :common        -> <externalGradleRoot>/build/common
//   :server        -> <externalGradleRoot>/build/server
//
// If nested projects are ever added, paths remain unique:
//   :foo:bar       -> <externalGradleRoot>/build/foo/bar
// ==========================================================
gradle.beforeProject {
    group = projectGroup
    version = projectVersion

    // Expose version to all projects via extra properties
    extensions.extraProperties["centralVersion"] = projectVersion

    // Redirect this project's build directory only when an external root is configured.
    if (externalGradleRoot != null) {
        val projectPathSafe =
            project.path
                .removePrefix(":")
                .replace(":", "/")
                .ifEmpty { "root" }

        val projectBuildDir = File(externalGradleRoot, "build/$projectPathSafe")
        layout.buildDirectory.set(projectBuildDir)
    }

    // Log the applied configuration for visibility/debugging.
    println(
        buildString {
            append("Configured project: $path → Version: $version")
            if (externalGradleRoot != null) {
                append(" → ExternalGradleRoot: $externalGradleRoot")
            }
        }
    )
}

// ==========================================================
// Module Includes
// ==========================================================

include("common")      // Shared code (models)
include("server")      // Backend logic (services, data, external, server API)
include("app")         // Shared frontend logic (KMP library: API clients, ViewModels, UI)
include("androidApp")  // Android application entry point
include("desktopApp")  // Desktop (JVM) application entry point
include("webApp")      // Web (Wasm/JS) application entry point
include("worker")      // Standalone worker service logic

// Include the custom build logic
includeBuild("build-logic")

// ==========================================================
// Plugin Management
// ==========================================================

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