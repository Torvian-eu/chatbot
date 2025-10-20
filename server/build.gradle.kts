/**
 * Build configuration for the `server` module.
 *
 * - Implements a Ktor-based central server.
 */

description = "Central server module for the chatbot application"

plugins {
    id("common-module-convention")  // Apply custom convention plugin
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":common"))            // Shared logic

    // Bundles of dependencies
    implementation(libs.bundles.ktor.server)     // Ktor server-related dependencies
    implementation(libs.bundles.ktor.client)     // Ktor client-related dependencies (for LLM interaction)
    implementation(libs.bundles.exposed)         // Exposed ORM for database handling
    implementation(libs.bundles.log4j)           // Logging with Log4j
    implementation(libs.bundles.koin)            // Koin dependency injection
    implementation(libs.bundles.bouncycastle)    // Bouncy Castle for certificate operations

    // Individual libraries
    implementation(libs.serialization.json)           // KotlinX Serialization JSON
    implementation(libs.coroutines.core)              // KotlinX Coroutines Core
    implementation(libs.kotlinx.datetime)             // KotlinX DateTime
    implementation(libs.ktor.serialization.json)      // Ktor JSON serialization support
    implementation(libs.ktor.network.tls.certificates) // Ktor TLS certificates for SSL/HTTPS
    implementation(libs.sqlite.jdbc)                  // SQLite JDBC Driver
    implementation(libs.jbcrypt)                      // Password hashing library
    implementation(libs.typesafe.config)              // Typesafe Config for configuration
    implementation(libs.arrow.core)                   // Arrow Core for functional programming

    // Testing dependencies
    testImplementation(libs.bundles.ktor.server.test)  // Ktor server testing
    testImplementation(libs.bundles.ktor.client)       // Ktor client-related dependencies
    testImplementation(libs.ktor.client.mock)          // Ktor client mock engine for testing
    testImplementation(libs.kotlin.test)               // Kotlin test framework
    testImplementation(libs.bundles.koin.test)         // Koin testing support
    testImplementation(libs.mockk)                     // Mocking library
}

// Define the main application entry point
application {
    mainClass.set("eu.torvian.chatbot.server.main.ServerMainKt")
}
