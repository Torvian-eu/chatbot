import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

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
    alias(libs.plugins.compose.hotreload)
    alias(libs.plugins.android.application)
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
            // Run tests in parallel within a class
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            // Run tests from the same class in the same thread to avoid concurrency issues
            systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
            // Dynamic parallelism strategy
            systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
            // Dynamic parallelism factor (50% of available processors)
            systemProperty("junit.jupiter.execution.parallel.config.dynamic.factor", "0.5")
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        //moduleName = "composeApp"
        outputModuleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
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
        compilerOptions {
            // Use the new Wasm exception proposal for better error handling
            freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
        }
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Add other targets here:
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
            implementation(compose.materialIconsExtended)

            // AndroidX Lifecycle
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)

            // AndroidX Compose Navigation
            implementation(libs.androidx.navigation.compose)

            // Ktor Client
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.resources)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.auth)

            // Arrow dependencies for Either
            implementation(libs.arrow.core)
            implementation(libs.arrow.fx.coroutines)

            // KotlinX dependencies
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)

            // Koin dependency injection
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
            implementation(libs.ktor.client.mock)

            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }

        desktopMain.dependencies {
            // implementation(project(":server"))
            // Compose for Desktop
            runtimeOnly(compose.desktop.currentOs)
            // KotlinX Coroutines Swing for JVM Main Dispatcher
            runtimeOnly(libs.kotlinx.coroutines.swing)
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

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            // Ktor Client Engine (Android-specific)
            implementation(libs.ktor.client.cio)
            // Logging
            implementation(libs.slf4j.simple)
        }
    }
}

// Task to create native installers (E7.S1)
compose.desktop {
    application {
        mainClass = "eu.torvian.chatbot.app.main.AppMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Chatbot"
            packageVersion = "1.0.0"

            // Add launcher configuration if needed
            // linux { ... }
            // windows { ... }
            // macos { ... }
        }

        // Uncomment to test with Spanish locale
//        jvmArgs += "-Duser.language=es"
//        jvmArgs += "-Duser.country=ES" // Optional, for regional variants like es-ES

    }
}

compose {
    resources {
        // Set the package name for generated resources
        packageOfResClass = "eu.torvian.chatbot.app.generated.resources"
    }
}

android {
    namespace = "eu.torvian.chatbot"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "eu.torvian.chatbot"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // UI tooling for debugging and preview
    debugImplementation(compose.uiTooling)
}
