import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

//import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
//import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

/**
 * Build configuration for the `app-shared` module.
 *
 * - Implements shared frontend logic for the application, including API clients and ViewModels.
 */

description = "Shared frontend logic module for the chatbot application"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
//    alias(libs.plugins.compose.hotreload)
}

repositories {
    mavenCentral()
    google()
}

// Define the Kotlin targets for this multiplatform module
kotlin {
    // Primary target for Desktop backend-frontend logic
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }

        testRuns["test"].executionTask.configure {
            // Use JUnit 5 Platform for testing
            useJUnitPlatform()

            // Enable dynamic agent loading for MockK and disable class data sharing (JVM args)
            jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")

            // Enable parallel test execution (JVM system properties)
            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
        }
    }

//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        moduleName = "composeApp"
//        browser {
//            val rootDirPath = project.rootDir.path
//            val projectDirPath = project.projectDir.path
//            commonWebpackConfig {
//                outputFileName = "composeApp.js"
//                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
//                    static = (static ?: mutableListOf()).apply {
//                        // Serve sources to debug inside browser
//                        add(rootDirPath)
//                        add(projectDirPath)
//                    }
//                }
//            }
//        }
//        binaries.executable()
//    }

    // Add other targets here:
    // androidTarget()
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    // Define the source sets for this module
    // Source sets are used to share code between targets
    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            // Project dependencies
            // This module depends on the 'common' module for shared DTOs, ApiError etc.
            implementation(project(":common"))

            // Compose dependencies
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // AndroidX Lifecycle
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)

            // Ktor Client
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.resources)

            // Arrow dependencies for Either
            implementation(libs.arrow.core)
            implementation(libs.arrow.fx.coroutines)

            // KotlinX dependencies
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // Koin dependency injection
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.bundles.koin.test)
            implementation(libs.ktor.client.mock)

            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }

        desktopMain.dependencies {
            implementation(project(":server"))
            // Compose for Desktop
            implementation(compose.desktop.currentOs)
            // KotlinX Coroutines Swing for JVM Main Dispatcher
            implementation(libs.kotlinx.coroutines.swing)
            // Ktor Client Engine (JVM-specific)
            implementation(libs.ktor.client.cio)
            // Logging (JVM-specific)
            implementation(libs.log4j.api)
            runtimeOnly(libs.log4j.core)
            runtimeOnly(libs.log4j.slf4j2)

        }
        desktopTest.dependencies {
            // Mocking library (JVM-specific)
            implementation(libs.mockk)
        }
    }
}

// Task to create native installers (E7.S1)
compose.desktop {
    application {
        mainClass = "eu.torvian.chatbot.app.mainAppMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Chatbot"
            packageVersion = "1.0.0"

            // Add launcher configuration if needed
            // linux { ... }
            // windows { ... }
            // macos { ... }
        }
    }
}