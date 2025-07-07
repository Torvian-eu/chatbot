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
    jvm("desktop") // Primary target for Desktop backend-frontend logic

    // Add other targets here if you plan to expand to Android, iOS, Web, etc.
    // androidTarget()
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            // Project dependencies
            // This module depends on the 'common' module for shared DTOs, ApiError etc.
            implementation(project(":common"))
            implementation(project(":server"))

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

            // KotlinX dependencies
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // Koin dependency injection
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Logging
            implementation(libs.bundles.log4j)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.bundles.koin.test)
            implementation(libs.mockk)
            implementation(libs.ktor.client.mock)

            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }

        desktopMain.dependencies {
            // Ktor Client Engine (JVM-specific)
            implementation(libs.ktor.client.cio)
        }
    }
}

