description = "Standalone worker service module for MCP execution"

plugins {
    id("common-module-convention")
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":common"))

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.resources)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.okhttp)

    // Other libraries
    implementation(libs.arrow.core)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.io.core)
    implementation(libs.koin.core)
    implementation(libs.bundles.log4j)
    implementation(libs.bundles.bouncycastle)

    // MCP runtime dependencies
    implementation(libs.mcp.sdk.core)
    implementation(libs.mcp.sdk.client)

    // Testing dependencies
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
}

// Define the main application entry point
application {
    mainClass.set("eu.torvian.chatbot.worker.main.WorkerMain")
    applicationDefaultJvmArgs = listOf(
        "-Xmx2G", // Max heap size
        "-Xms512M" // Initial heap size
    )
}

// Configure Tasks
tasks {
    // Custom task to clean the installDist directory before each installDist execution
    register<Delete>("cleanInstallDistDir") {
        delete(layout.buildDirectory.dir("install/worker"))
    }

    // Ensure the installDist task depends on cleaning the install directory first
    named("installDist") {
        dependsOn("cleanInstallDistDir")
    }

    // Disable the default start scripts generation
    named<CreateStartScripts>("startScripts") {
        enabled = false
    }
}