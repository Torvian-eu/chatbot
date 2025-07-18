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
    jvm()

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
