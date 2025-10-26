package eu.torvian.chatbot.app.service.auth

import arrow.core.getOrElse
import arrow.core.raise.either
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.common.models.api.auth.RefreshTokenRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*

private val logger = createKmpLogger("createAuthenticatedHttpClient")

/**
 * Adds authentication capabilities (with token refresh) to an EXISTING client.
 *
 * @param baseClient The pre-configured base HttpClient to add authentication to.
 *   This client should already have shared plugins and platform-specific engine config (e.g., SSL).
 * @param tokenStorage Token storage implementation
 * @param unauthenticatedHttpClient HTTP client for refresh token calls. It's crucial that this
 *   client does NOT have the Auth plugin to prevent infinite refresh loops.
 * @param eventBus Event bus for emitting authentication events
 * @return A new HttpClient instance configured with authentication support.
 */
fun createAuthenticatedHttpClient(
    baseClient: HttpClient,
    tokenStorage: TokenStorage,
    unauthenticatedHttpClient: HttpClient,
    eventBus: EventBus
): HttpClient {
    return baseClient.config {
        install(Auth) {
            bearer {
                loadTokens {
                    loadTokens(tokenStorage)
                }

                refreshTokens {
                    refreshTokens(tokenStorage, unauthenticatedHttpClient, eventBus)
                }

                sendWithoutRequest { request ->
                    // Automatically send Authorization header to all requests except (non-logout) auth endpoints
                    !request.url.encodedPath.startsWith(href(AuthResource())) ||
                            request.url.encodedPath.startsWith(href(AuthResource.Logout())) ||
                            request.url.encodedPath.startsWith(href(AuthResource.LogoutAll()))
                }
            }
        }
    }
}

private suspend fun loadTokens(
    tokenStorage: TokenStorage
): BearerTokens? {
    return either {
        val accessToken = tokenStorage.getAccessToken().bind()
        val refreshToken = tokenStorage.getRefreshToken().bind()
        BearerTokens(accessToken, refreshToken)
    }.getOrElse {
        logger.warn("Failed to load tokens: ${it.message}")
        null
    }
}

private suspend fun RefreshTokensParams.refreshTokens(
    tokenStorage: TokenStorage,
    unauthenticatedHttpClient: HttpClient,
    eventBus: EventBus
): BearerTokens? {
    val oldRefreshToken = oldTokens?.refreshToken ?: run {
        tokenStorage.clearAuthData()
            .onLeft {
                logger.warn("Failed to clear auth data: ${it.message}")
            }
        logger.warn("No refresh token available, clearing auth data")
        eventBus.emitEvent(
            AuthenticationFailureEvent(
                "No refresh token available, clearing auth data"
            )
        )
        return null
    }

    try {
        // Manual refresh token call using the provided unauthenticated client
        val refreshResponse: LoginResponse = unauthenticatedHttpClient.post(AuthResource.Refresh()) {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(oldRefreshToken))
        }.body()

        // Save the new tokens AND user data for optimistic authentication
        tokenStorage.saveAuthData(
            refreshResponse.accessToken,
            refreshResponse.refreshToken,
            refreshResponse.expiresAt,
            refreshResponse.user,
            refreshResponse.permissions
        ).fold(
            ifLeft = { error ->
                tokenStorage.clearAuthData()
                    .onLeft {
                        logger.warn("Failed to clear auth data after failed save: ${it.message}")
                    }
                logger.warn("Failed to save auth data after refresh: ${error.message}")
                eventBus.emitEvent(
                    AuthenticationFailureEvent(
                        "Failed to save auth data after refresh: ${error.message}"
                    )
                )
                return null
            },
            ifRight = {
                // Return new tokens for the auth plugin
                logger.debug("Successfully refreshed tokens and saved user data for: ${refreshResponse.user.username}")
                return BearerTokens(refreshResponse.accessToken, refreshResponse.refreshToken)
            }
        )
    } catch (e: Exception) {
        tokenStorage.clearAuthData()
            .onLeft {
                logger.warn("Failed to clear auth data after refresh exception: ${it.message}")
            }
        logger.warn("Refresh token request failed, clearing auth data: ${e.message}")
        eventBus.emitEvent(
            AuthenticationFailureEvent(
                "Refresh token request failed, clearing auth data: ${e.message}"
            )
        )
        return null // Return null to signify refresh failure
    }
}
