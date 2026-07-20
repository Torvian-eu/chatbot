import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Build configuration for the `app` module.
 *
 * This module is a Kotlin Multiplatform **library** that contains the shared
 * frontend logic for the application: API clients, ViewModels, shared Compose UI,
 * and the platform-specific `actual` implementations for Android, Desktop (JVM),
 * and Web (Wasm/JS).
 *
 * The platform entry points (MainActivity, desktop `main()`, Wasm `main()`) live
 * in the dedicated `androidApp`, `desktopApp`, and `webApp` modules respectively.
 * This split is required because AGP 9.0 no longer allows the Android
 * application plugin to coexist with the Kotlin Multiplatform plugin in a single
 * module.
 */

description = "Shared frontend logic library module for the chatbot application"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
//    alias(libs.plugins.compose.hotreload)
    // AGP 9.0 KMP library plugin: replaces com.android.library for KMP modules.
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

repositories {
    mavenCentral()
    google()
}

// Define the Kotlin targets for this multiplatform module
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.javaVersion.get().toInt()))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    // Primary target for Desktop backend-frontend logic.
    // Desktop is consumed as a library by the :desktopApp module; no application block here.
    jvm("desktop") {
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
        // Library target only: the executable/browser configuration lives in the :webApp module.
        // Keep this target so the shared `actual` implementations (wasmJsMain) are compiled
        // and consumed by :webApp. Select the browser environment to silence the AGP/Kotlin
        // "choose a WebAssembly-JavaScript environment" warning.
        browser()
    }

    // Android target configuration lives in the `android {}` block (AGP 9.0 KMP library plugin).
    android {
        namespace = "eu.torvian.chatbot.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaAndroid.get()))
        }

        // Enable Android resources so the shared module can expose composeResources and
        // the launcher resources consumed by the :androidApp module.
        androidResources { enable = true }

        // Enable host (JVM) unit tests for the Android source set so the shared
        // commonTest code can run on the Android target.
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    // Apply the default hierarchy template again. Needed for custom source sets to work correctly.
    applyDefaultHierarchyTemplate()

    // Define the source sets for this module
    // Source sets are used to share code between targets
    sourceSets {
        // Create a new source set for shared Android/Desktop code
        val desktopAndroidMain = create("desktopAndroidMain") {
            dependsOn(commonMain.get())
        }
        val desktopMain = getByName("desktopMain") {
            dependsOn(desktopAndroidMain)
        }
        androidMain {
            dependsOn(desktopAndroidMain)
        }
        val desktopTest = getByName("desktopTest")

        commonMain.dependencies {
            // Project dependencies
            // This module depends on the 'common' module for shared DTOs, ApiError etc.
            implementation(project(":common"))

            // Compose dependencies
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            //TODO: Instead of using the now deprecated material-icons-extended,
            // download icons individually from google and add them to /composeResources.
            // As described here: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html#icons
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
            implementation(libs.ktor.client.websockets)

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

            // MCP Kotlin SDK
            implementation(libs.mcp.sdk.core)
            implementation(libs.mcp.sdk.client)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
            implementation(libs.ktor.client.mock)

            implementation(libs.compose.uiTest)
        }

        desktopMain.dependencies {
            // Compose for Desktop
            implementation(compose.desktop.currentOs)
            // KotlinX Coroutines Swing for JVM Main Dispatcher
            implementation(libs.kotlinx.coroutines.swing)
            // Logging (JVM-specific)
            implementation(libs.log4j.api)
            runtimeOnly(libs.log4j.core)
            runtimeOnly(libs.log4j.slf4j2)
        }
        desktopTest.dependencies {
            // Mocking library (JVM-specific)
            implementation(libs.mockk)
        }

        desktopAndroidMain.dependencies {
            // Use OkHttp engine for Desktop and Android (works better than CIO engine)
            implementation(libs.ktor.client.okhttp)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // Koin Android integration (provides androidContext() for DI).
            implementation(libs.koin.android)
            // Logging
            implementation(libs.slf4j.simple)
        }

        wasmJsMain.dependencies {
            // JavaScript engine for WasmJS target
            implementation(libs.ktor.client.js)
        }
    }
}

// Get the central version from settings.gradle.kts
val centralVersion = extensions.extraProperties["centralVersion"]?.toString() ?: "unspecified"

// Task to generate VersionInfo.kt with the project version
tasks.register("generateVersionInfo") {
    group = "build"
    description = "Generates VersionInfo.kt with the project version for use in commonMain"

    // Capture version in configuration phase for configuration cache compatibility
    val appVersion = centralVersion
    inputs.property("appVersion", appVersion)
    val outDir = layout.buildDirectory.dir("generated/version/commonMain/kotlin")
    outputs.dir(outDir)

    doLast {
        val packagePath = "eu/torvian/chatbot/app"
        val outputDirFile = outDir.get().asFile
        val packageDir = File(outputDirFile, packagePath)
        packageDir.mkdirs()

        val versionInfoFile = File(packageDir, "VersionInfo.kt")
        versionInfoFile.writeText(
            """
            package eu.torvian.chatbot.app

            /**
             * Version information for the Chatbot application.
             * This file is auto-generated by the generateVersionInfo task.
             */
            object VersionInfo {
                const val VERSION = "$appVersion"
            }
            """.trimIndent()
        )

        println("Generated VersionInfo.kt with version: $appVersion")
    }
}

// Register the generated source directory for commonMain using task provider
kotlin.sourceSets.commonMain.configure {
    kotlin.srcDir(tasks.named("generateVersionInfo"))
}

// Compose resources configuration for the shared module.
compose {
    resources {
        // Set the package name for generated resources
        packageOfResClass = "eu.torvian.chatbot.app.generated.resources"
    }
}

tasks.withType<Test> {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow" // Allow unsafe memory access for testing purposes (needed for MockK)
    )
}
