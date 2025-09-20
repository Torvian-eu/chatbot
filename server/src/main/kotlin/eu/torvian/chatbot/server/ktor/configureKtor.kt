package eu.torvian.chatbot.server.ktor

import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.domain.security.JwtConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

/**
 * Configures the Ktor server with necessary plugins and settings. (shared with tests)
 */
fun Application.configureKtor(jwtConfig: JwtConfig) {
    // Install the ContentNegotiation plugin for JSON serialization
    install(ContentNegotiation) {
        json(Json)
    }

    // Install the Resources plugin for type-safe routing
    install(Resources)

    // Install the SSE plugin for server-sent events support
    install(SSE)

    // Install Authentication plugin with JWT
    install(Authentication) {
        jwt(AuthSchemes.USER_JWT) {
            realm = jwtConfig.realm
            verifier(jwtConfig.verifier)
            validate { credential ->
                if (credential.payload.audience.contains(jwtConfig.audience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }
}