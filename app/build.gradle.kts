@file:OptIn(ExperimentalComposeLibrary::class)

import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * Build configuration for the `app` module.
 *
 * - Implements a Compose for Desktop UI.
 */

description = "Desktop application module for the chatbot application"

plugins {
    id("common-module-convention")  // Apply custom convention plugin
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    google()
}

dependencies {
    implementation(project(":common"))          // Shared logic
    implementation(project(":server"))          // Server logic (will be removed later, when decoupled)

    // Bundles of dependencies
    implementation(libs.bundles.ktor.client)     // Ktor client-related dependencies
    implementation(libs.bundles.log4j)           // Logging with Log4j
    implementation(libs.bundles.koin)            // Koin dependency injection

    // Individual libraries
    implementation(libs.serialization.json)           // KotlinX Serialization JSON
    implementation(libs.coroutines.core)              // KotlinX Coroutines Core
    implementation(libs.kotlinx.datetime)             // KotlinX DateTime
    implementation(libs.ktor.serialization.json)      // Ktor JSON serialization support
    implementation(libs.typesafe.config)              // Typesafe Config for configuration
    implementation(compose.desktop.currentOs)

    // Testing dependencies
    testImplementation(libs.kotlin.test)               // Kotlin test framework
    testImplementation(libs.kotlinx.coroutines.test)   // KotlinX Coroutines Test
    testImplementation(libs.bundles.koin.test)         // Koin testing support
    testImplementation(libs.mockk)                     // Mocking library
    testImplementation(compose.uiTest)                 // Compose UI testing utilities
}

// Task to create native installers (E7.S1)
compose.desktop {
    application {
        mainClass = "eu.torvian.chatbot.app.AppMainKt"

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
