package eu.torvian.chatbot.server.ktor

import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.ktor.auth.extractJwtAuthHeader
import eu.torvian.chatbot.server.service.security.AuthenticationService
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json

/**
 * Configures the Ktor server with necessary plugins and settings. (shared with tests)
 */
fun Application.configureKtor(jwtConfig: JwtConfig, authService: AuthenticationService) {
    // Install the ContentNegotiation plugin for JSON serialization
    install(ContentNegotiation) {
        json(Json)
    }

    // Install the Resources plugin for type-safe routing
    install(Resources)

    // Install the SSE plugin for server-sent events support
    install(SSE)

    // Install the WebSockets plugin for bidirectional communication
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }

    // Install Authentication plugin with JWT
    install(Authentication) {
        jwt(AuthSchemes.USER_JWT) {
            realm = jwtConfig.realm
            verifier(jwtConfig.userVerifier)
            // Browser WebSocket clients cannot set Authorization headers directly.
            // This extractor keeps normal Bearer auth and adds subprotocol fallback for WS handshakes.
            authHeader { call ->
                call.request.extractJwtAuthHeader()
            }
            validate { credential ->
                authService.validateCredential(credential)
            }
        }

        jwt(AuthSchemes.WORKER_JWT) {
            realm = jwtConfig.realm
            verifier(jwtConfig.workerVerifier)
            validate { credential ->
                authService.validateWorkerCredential(credential)
            }
        }
    }
}