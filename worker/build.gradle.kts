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
    implementation(libs.koin.core)
    implementation(libs.bundles.log4j)
    implementation(libs.bundles.bouncycastle)

    // Testing dependencies
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("eu.torvian.chatbot.worker.main.WorkerMain")
}

