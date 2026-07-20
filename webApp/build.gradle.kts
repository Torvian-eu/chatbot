import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

repositories {
    mavenCentral()
    google()
}

/**
 * Build configuration for the `webApp` module.
 *
 * This is a Kotlin/Wasm application module that contains only the web entry point
 * (`AppMain`). All shared frontend logic lives in the `:app` KMP library module,
 * which this module depends on. The Wasm/JS browser and distribution configuration
 * lives here.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = devServer ?: KotlinWebpackConfig.DevServer().apply {
                    // Serve sources to debug inside browser
                    static(rootDirPath)
                    static(projectDirPath)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // Shared frontend logic (KMP library module).
            implementation(project(":app"))

            // Koin Compose integration (provides modules() and Koin application support).
            implementation(libs.koin.compose)

            // Compose Multiplatform UI dependencies for the web target.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}
