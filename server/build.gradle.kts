/**
 * Build configuration for the `server` module.
 *
 * - Implements a Ktor-based central server.
 */

description = "Central server module for the chatbot application"

plugins {
    id("common-module-convention")  // Apply custom convention plugin
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
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
    implementation(libs.ktor.server.jetty)            // Ktor Jetty engine for server
    implementation(libs.ktor.serialization.json)      // Ktor JSON serialization support
    implementation(libs.ktor.network.tls.certificates) // Ktor TLS certificates for SSL/HTTPS
    implementation(libs.sqlite.jdbc)                  // SQLite JDBC Driver
    implementation(libs.jbcrypt)                      // Password hashing library
    implementation(libs.typesafe.config)              // Typesafe Config for configuration
    implementation(libs.arrow.core)                   // Arrow Core for functional programming
    implementation(libs.hikaricp)                     // HikariCP for connection pooling
    implementation(libs.flyway.core)                  // Flyway for schema migrations

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
    mainClass.set("eu.torvian.chatbot.server.main.ServerMain")
    applicationDefaultJvmArgs = listOf(
        "-Xmx2G", // Max heap size
        "-Xms512M" // Initial heap size
    )
}

// Configure Tasks
tasks {
    // Configure the shadow JAR task
    shadowJar {
        mergeServiceFiles() // merge the contents from META-INF/services/* files, in case of duplicate file names
    }

    // Custom task to clean the installDist directory before each installDist execution
    register<Delete>("cleanInstallDistDir") {
        delete(layout.buildDirectory.dir("install/server"))
    }

    // Ensure the installDist task depends on cleaning the install directory first
    named("installDist") {
        dependsOn("cleanInstallDistDir")
    }

    // Task to install the server distribution to a custom path
    register<Copy>("installDistTo") {
        group = "distribution"
        description = "Installs the server distribution to a custom path using -PinstallPath=<path>."

        dependsOn(named("installDist"))
        from(layout.buildDirectory.dir("install/server"))
        into(provider {
            val installPath = findProperty("installPath")?.toString()?.trim()
            if (installPath.isNullOrEmpty()) {
                throw GradleException(
                    "Missing required property 'installPath'. Usage: ./gradlew server:installDistTo -PinstallPath=/your/target/path"
                )
            }
            file(installPath)
        })
    }

    // Disable the default start scripts generation
    named<CreateStartScripts>("startScripts") {
        enabled = false
    }

    // Disable shadow start scripts generation (we use custom scripts in src/main/dist)
    named<CreateStartScripts>("startShadowScripts") {
        enabled = false
    }
}

// Configure ALL distributions (Main and Shadow) at once
distributions.configureEach {
    contents {
        // Include everything from src/main/dist (scripts, etc.)
        // This is automatically included for 'main', but this line
        // ensures 'shadow' and any other custom dist gets it too.
        from("src/main/dist")
    }
}
