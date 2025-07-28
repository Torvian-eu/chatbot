import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

/**
 * Build configuration for the `common` module.
 *
 * - Contains shared logic and data classes.
 */

description = "Shared logic and data classes for the chatbot application"

plugins {
//    id("common-module-convention")  // Apply custom convention plugin
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "composeCommon"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeCommon.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)           // Koin dependency injection
            implementation(libs.ktor.resources)      // Ktor resources for type-safe routing
            implementation(libs.serialization.json)  // KotlinX Serialization JSON
            implementation(libs.kotlinx.datetime)    // KotlinX DateTime

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
