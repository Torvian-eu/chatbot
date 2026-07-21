import java.io.File
import java.util.Properties

// ==========================================================
// Optional External Gradle Artifact Configuration
// for the included build: build-logic
// ==========================================================
//
// This included build is a separate Gradle build, so it needs
// its own cache/build directory redirection.
//
// Precedence:
// 1. Environment variable: CHATBOT_GRADLE_ROOT
// 2. Root project file:    ../local.gradle.properties
// 3. Otherwise:            default Gradle behavior
//
// If an external root is configured, this included build uses:
//   <externalRoot>/build-logic/...
// ==========================================================

// local.gradle.properties lives in the main repository root,
// one directory above this build-logic folder.
val localGradlePropertiesFile = File(settingsDir.parentFile, "local.gradle.properties")

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

val gradleRootRaw: String? =
    System.getenv("CHATBOT_GRADLE_ROOT")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: localGradleRoot

val configuredExternalGradleRoot: File? =
    gradleRootRaw?.let { rawPath ->
        val configuredPath = File(rawPath)
        val resolvedPath =
            if (configuredPath.isAbsolute) {
                configuredPath
            } else {
                File(settingsDir.parentFile, rawPath)
            }

        resolvedPath.canonicalFile
    }

// Use a dedicated namespace for this included build.
val externalGradleRoot: File? =
    configuredExternalGradleRoot?.let { File(it, "build-logic") }

if (externalGradleRoot != null) {
    startParameter.projectCacheDir = File(externalGradleRoot, "project-cache")
}

gradle.beforeProject {
    if (externalGradleRoot != null) {
        val projectPathSafe =
            project.path
                .removePrefix(":")
                .replace(":", "/")
                .ifEmpty { "root" }

        val projectBuildDir = File(externalGradleRoot, "build/$projectPathSafe")
        layout.buildDirectory.set(projectBuildDir)
    }
}

// Use the version catalog from the root project to manage dependencies and plugins.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}