package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.AuthApi
import eu.torvian.chatbot.app.service.auth.AuthenticationFailureEvent
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.auth.LoginRequest
import eu.torvian.chatbot.common.models.auth.RegisterRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Default implementation of the AuthRepository interface.
 *
 * This repository manages authentication state using StateFlow and coordinates
 * between the AuthApi for server operations and TokenStorage for local token management.
 *
 * @property authApi The API client for authentication operations
 * @property tokenStorage The storage for managing authentication tokens
 */
class DefaultAuthRepository(
    private val authApi: AuthApi,
    private val tokenStorage: TokenStorage,
    private val eventBus: EventBus
) : AuthRepository {

    companion object {
        private val logger = kmpLogger<DefaultAuthRepository>()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.Default)

    init {
        repositoryScope.launch {
            eventBus.events.collect { event ->
                if (event is AuthenticationFailureEvent) {
                    logger.warn("Received AuthenticationFailureEvent: ${event.reason}.")
                    _authState.value = AuthState.Unauthenticated
                }
            }
        }
    }

    override suspend fun login(request: LoginRequest): Either<RepositoryError, Unit> = either {
        _authState.value = AuthState.Loading

        // Perform login API call
        val loginResponse = withError({ apiError ->
            _authState.value = AuthState.Unauthenticated
            apiError.toRepositoryError("Login failed")
        }) {
            authApi.login(request).bind()
        }

        // Save authentication data (tokens, user, and permissions)
        withError({ tokenError ->
            _authState.value = AuthState.Unauthenticated
            RepositoryError.OtherError("Failed to save authentication data after successful login: ${tokenError.message}")
        }) {
            tokenStorage.saveAuthData(
                accessToken = loginResponse.accessToken,
                refreshToken = loginResponse.refreshToken,
                expiresAt = loginResponse.expiresAt,
                user = loginResponse.user,
                permissions = loginResponse.permissions
            ).bind()
        }

        // Update auth state on successful token save
        _authState.value = AuthState.Authenticated(
            userId = loginResponse.user.id,
            username = loginResponse.user.username,
            permissions = loginResponse.permissions
        )
    }

    override suspend fun register(request: RegisterRequest): Either<RepositoryError, User> = either {
        withError({ apiError ->
            apiError.toRepositoryError("Registration failed")
        }) {
            authApi.register(request).bind()
        }
    }

    override suspend fun logout(): Either<RepositoryError, Unit> = either {
        withError({ apiError ->
            apiError.toRepositoryError("Logout failed")
        }) {
            authApi.logout().bind()
        }
        tokenStorage.clearAuthData()
            .onLeft { logger.warn("Failed to clear auth data on logout: ${it.message}") }

        _authState.value = AuthState.Unauthenticated
    }

    override suspend fun isAuthenticated(): Boolean {
        return _authState.value is AuthState.Authenticated
    }

    override suspend fun checkInitialAuthState() {
        logger.info("Checking initial authentication state on startup")

        // Try to load cached user data - NO network calls for optimistic authentication
        tokenStorage.getUserData().fold(
            ifLeft = { error ->
                logger.debug("Failed to load cached user data, setting unauthenticated state: ${error.message}")
                _authState.value = AuthState.Unauthenticated
            },
            ifRight = { user ->
                // Also load permissions
                tokenStorage.getPermissions().fold(
                    ifLeft = { error ->
                        logger.warn("Failed to load permissions, setting unauthenticated state: ${error.message}")
                        _authState.value = AuthState.Unauthenticated
                    },
                    ifRight = { permissions ->
                        logger.info("Found cached user data and permissions, setting authenticated state: ${user.username}")
                        _authState.value = AuthState.Authenticated(user.id, user.username, permissions)
                    }
                )
            }
        )
    }
}
