/**
 * Build configuration for the `common` module.
 *
 * - Contains shared logic and data classes.
 */

description = "Shared logic and data classes for the chatbot application"

plugins {
    id("common-module-convention")  // Apply custom convention plugin
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Bundles of dependencies
    implementation(libs.bundles.log4j)           // Logging with Log4j
    implementation(libs.bundles.bouncycastle)    // BouncyCastle for cryptographic operations
    implementation(libs.bundles.koin)            // Koin dependency injection
    implementation(libs.bundles.exposed)         // Exposed ORM for database operations
    implementation(libs.bundles.ktor.client)     // Ktor HTTP client

    // Individual libraries
    implementation(libs.serialization.json)           // KotlinX Serialization JSON
    implementation(libs.coroutines.core)              // KotlinX Coroutines Core
    implementation(libs.kotlinx.datetime)             // KotlinX DateTime
    implementation(libs.jbcrypt)                      // Password hashing library
    implementation(libs.sqlite.jdbc)                  // SQLite JDBC driver

    // Testing dependencies
    testImplementation(libs.kotlin.test)               // Kotlin test framework
}
