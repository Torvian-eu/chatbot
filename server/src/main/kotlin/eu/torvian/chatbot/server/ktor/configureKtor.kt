package eu.torvian.chatbot.server.ktor

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import kotlinx.serialization.json.Json

/**
 * Configures the Ktor server with necessary plugins and settings. (shared with tests)
 */
fun Application.configureKtor() {
    // Install the ContentNegotiation plugin for JSON serialization
    install(ContentNegotiation) {
        json(Json)
    }

    // Install the Resources plugin for type-safe routing
    install(Resources)
}