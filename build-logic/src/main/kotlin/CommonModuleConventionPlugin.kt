import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * A Gradle convention plugin that applies common build configurations
 * to all modules in the project.
 *
 * Responsibilities:
 * - Apply the Kotlin JVM plugin dynamically using the version catalog.
 * - Configure Java toolchain version based on the shared `libs.versions.toml`.
 * - Add shared repositories (e.g., Maven Central).
 * - Configure the default testing framework (JUnit Platform).
 * - Handle potential errors with clear, actionable messages.
 */
class CommonModuleConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {

            // Access the version catalog (`libs.versions.toml`)
            val versionCatalog = try {
                extensions.getByType<VersionCatalogsExtension>().named("libs")
            } catch (e: Exception) {
                logger.error("❌ Version catalog `libs` not found. Make sure it's defined in `libs.versions.toml`.")
                throw IllegalStateException("Failed to find version catalog `libs`.", e)
            }

            // Apply the Kotlin JVM plugin dynamically using the version catalog
            applyKotlinJvmPlugin(versionCatalog)

            // Add shared repositories
            configureRepositories()

            // Configure testing framework (JUnit 5)
            configureTesting()

            // Set Java toolchain version dynamically
            configureJavaToolchain(versionCatalog)
        }
    }

    /**
     * Applies the Kotlin JVM plugin dynamically using the version catalog.
     *
     * @param versionCatalog The version catalog that contains plugin references.
     */
    private fun Project.applyKotlinJvmPlugin(versionCatalog: VersionCatalog) {
        val kotlinJvmPluginId = versionCatalog.findPlugin("kotlin-jvm")
            .orElse(null)
            ?.get()
            ?.pluginId ?: run {
            logger.error("❌ Kotlin JVM plugin not found in `libs.versions.toml`. Please ensure it's defined under [plugins].")
            throw IllegalStateException("Kotlin JVM plugin missing in version catalog.")
        }

        plugins.apply(kotlinJvmPluginId)
        logger.lifecycle("✅ Applied Kotlin JVM plugin: $kotlinJvmPluginId")
    }

    /**
     * Configures the standard repositories for all modules.
     */
    private fun Project.configureRepositories() {
        repositories.mavenCentral()
        logger.lifecycle("✅ Configured Maven Central as a repository.")
    }

    /**
     * Configures the default testing framework (JUnit Platform) for all modules.
     */
    private fun Project.configureTesting() {
        tasks.withType<Test>().configureEach {
            // Use JUnit 5 Platform for testing
            useJUnitPlatform()
            // Enable dynamic agent loading for MockK and disable class data sharing
            jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
            // Enable parallel test execution
            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
        }
        logger.lifecycle("✅ Configured JUnit Platform for testing.")
        logger.lifecycle("✅ Enabled dynamic agent loading for MockK and disabled class data sharing.")
        logger.lifecycle("✅ Enabled parallel test execution.")
    }

    /**
     * Configures the Kotlin JVM toolchain dynamically using the version from the version catalog.
     *
     * @param versionCatalog The version catalog containing the Java version.
     */
    private fun Project.configureJavaToolchain(versionCatalog: VersionCatalog) {
        extensions.configure(KotlinJvmProjectExtension::class.java) {
            jvmToolchain {
                val javaVersion = versionCatalog.findVersion("javaVersion")
                    .orElse(null)
                    ?.requiredVersion
                    ?.toIntOrNull()
                    ?: run {
                        logger.error("❌ Java version not found or invalid in `libs.versions.toml`. Please define `javaVersion` in the [versions] section.")
                        throw IllegalStateException("Java version missing or malformed in version catalog.")
                    }

                languageVersion.set(JavaLanguageVersion.of(javaVersion))
                logger.lifecycle("✅ Configured Java toolchain with version: $javaVersion")
            }
        }
    }
}
