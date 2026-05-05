package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.domain.events.AccountSwitchedEvent
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.AuthApi
import eu.torvian.chatbot.app.service.api.UserApi
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.service.auth.AuthenticationFailureEvent
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.user.User
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
 * @property userApi The API client for user management operations
 * @property tokenStorage The storage for managing authentication tokens
 */
class DefaultAuthRepository(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val tokenStorage: TokenStorage,
    private val eventBus: EventBus
) : AuthRepository {

    companion object {
        private val logger = kmpLogger<DefaultAuthRepository>()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _availableAccounts = MutableStateFlow<List<AccountData>>(emptyList())
    override val availableAccounts: StateFlow<List<AccountData>> = _availableAccounts.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.Default)

    init {
        repositoryScope.launch {
            eventBus.events.collect { event ->
                if (event is AuthenticationFailureEvent) {
                    logger.warn("Received AuthenticationFailureEvent: ${event.reason}.")
                    _authState.value = AuthState.Unauthenticated
                    refreshAvailableAccounts()
                }
            }
        }
    }

    override suspend fun login(username: String, password: String): Either<RepositoryError, Unit> = either {
        logger.info("Logging in with username: $username")

        _authState.value = AuthState.Loading

        // Perform login API call
        val loginResponse = withError({ apiError ->
            _authState.value = AuthState.Unauthenticated
            apiError.toRepositoryError("Login failed")
        }) {
            authApi.login(username, password).bind()
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
            permissions = loginResponse.permissions,
            requiresPasswordChange = loginResponse.user.requiresPasswordChange
        )

        // Refresh available accounts list
        refreshAvailableAccounts()

        logger.info("Login successful for user: ${loginResponse.user.username}")
    }

    override suspend fun register(username: String, password: String, email: String?): Either<RepositoryError, User> =
        either {
            logger.info("Registering new user: $username")

            withError({ apiError ->
                apiError.toRepositoryError("Registration failed")
            }) {
                authApi.register(username, password, email).bind()
            }.also {
                logger.info("Registration successful for user: ${it.username}")
            }
        }

    override suspend fun changePassword(userId: Long, newPassword: String): Either<RepositoryError, Unit> = either {
        logger.info("Changing password for user: $userId")

        // Call the API to change password
        withError({ apiError: ApiResourceError ->
            apiError.toRepositoryError("Password change failed")
        }) {
            userApi.changeUserPassword(userId, newPassword).bind()
        }

        logger.info("Password changed successfully for user: $userId")
    }

    override suspend fun getActiveSessions(): Either<RepositoryError, List<UserSessionInfo>> = either {
        logger.info("Fetching active sessions for the current authenticated user")

        withError({ apiError ->
            apiError.toRepositoryError("Failed to load active sessions")
        }) {
            authApi.getActiveSessions().bind()
        }
    }

    override suspend fun revokeSession(sessionId: Long): Either<RepositoryError, Unit> = either {
        logger.info("Revoking session with sessionId: $sessionId")

        withError({ apiError ->
            apiError.toRepositoryError("Failed to revoke session")
        }) {
            authApi.logout(sessionId).bind()
        }

        logger.info("Successfully revoked session with sessionId: $sessionId")
    }

    override suspend fun logout(): Either<RepositoryError, Unit> = either {
        logger.info("Logging out")
        val result = authApi.logout()

        // Clear local auth data
        tokenStorage.clearAuthData()
            .onLeft { logger.warn("Failed to clear auth data on logout: ${it.message}") }

        // Refresh available accounts list
        refreshAvailableAccounts()

        // Update auth state
        _authState.value = AuthState.Unauthenticated

        withError({ apiError ->
            apiError.toRepositoryError("Logout failed")
        }) {
            result.bind()
        }
        logger.info("Logout successful")
    }

    /**
     * Logs the current user out from every server-side session and clears the local active account.
     *
     * The local cleanup intentionally runs before the API result is bound so the client always
     * drops the active tokens once the logout-all flow has been initiated.
     */
    override suspend fun logoutAll(): Either<RepositoryError, Unit> = either {
        logger.info("Logging out from all sessions")
        val result = authApi.logoutAll()

        // Clear local auth data so the app stops using credentials from the active account.
        tokenStorage.clearAuthData()
            .onLeft { logger.warn("Failed to clear auth data on logout-all: ${it.message}") }

        // Refresh available accounts list so the UI reflects the cleared active account.
        refreshAvailableAccounts()

        // Reset the app to the unauthenticated state after logout-all.
        _authState.value = AuthState.Unauthenticated

        withError({ apiError ->
            apiError.toRepositoryError("Logout all sessions failed")
        }) {
            result.bind()
        }
        logger.info("Logout from all sessions successful")
    }

    override suspend fun isAuthenticated(): Boolean {
        return _authState.value is AuthState.Authenticated
    }

    override suspend fun checkInitialAuthState() {
        logger.info("Checking initial authentication state on startup")

        either {
            // Refresh available accounts list
            refreshAvailableAccounts()

            // Load cached data for active account
            val accountData = withError({ error ->
                RepositoryError.OtherError("Failed to load user data: ${error.message}")
            }) {
                tokenStorage.getAccountData().bind()
            }

            logger.info("Found cached user data for: ${accountData.user.username}")
            _authState.value = AuthState.Authenticated(
                userId = accountData.user.id,
                username = accountData.user.username,
                permissions = accountData.permissions,
                requiresPasswordChange = accountData.user.requiresPasswordChange
            )

            logger.info("Initial authentication state check complete")
        }.onLeft { error ->
            logger.warn("Failed to check initial auth state: ${error.message}")
            _authState.value = AuthState.Unauthenticated
        }

    }

    override suspend fun switchAccount(userId: Long): Either<RepositoryError, Unit> = either {
        logger.info("Switching to account with userId: $userId")

        // Get current user ID before switching
        val previousUserId = (_authState.value as? AuthState.Authenticated)?.userId

        // Load the new account's data
        val accountData = withError({ tokenError ->
            RepositoryError.OtherError("Failed to load account data: ${tokenError.message}")
        }) {
            tokenStorage.getAccountData(userId).bind()
        }

        // Switch the active account in storage
        withError({ tokenError ->
            RepositoryError.OtherError("Failed to switch account: ${tokenError.message}")
        }) {
            tokenStorage.switchAccount(userId).bind()
        }

        // Refresh available accounts list
        refreshAvailableAccounts()

        // Clear the in-memory token cache to force a reload on next API call
        authApi.clearToken()

        // Update auth state using the returned account data
        _authState.value = AuthState.Authenticated(
            userId = accountData.user.id,
            username = accountData.user.username,
            permissions = accountData.permissions,
            requiresPasswordChange = accountData.user.requiresPasswordChange
        )

        // Emit account switched event
        eventBus.emitEvent(AccountSwitchedEvent(previousUserId = previousUserId, newUserId = userId))

        logger.info("Successfully switched to account: ${accountData.user.username}")
    }

    override suspend fun removeAccount(userId: Long): Either<RepositoryError, Unit> = either {
        logger.info("Removing account with userId: $userId")

        // Check if this is the active account
        val currentAuthState = _authState.value
        val isActiveAccount = currentAuthState is AuthState.Authenticated && currentAuthState.userId == userId

        // Remove from storage
        withError({ tokenError ->
            RepositoryError.OtherError("Failed to remove account: ${tokenError.message}")
        }) {
            tokenStorage.removeAccount(userId).bind()
        }

        // If we removed the active account, set state to Unauthenticated
        if (isActiveAccount) {
            _authState.value = AuthState.Unauthenticated
            logger.info("Removed active account, auth state set to Unauthenticated")
        }

        // Refresh available accounts list
        refreshAvailableAccounts()

        logger.info("Successfully removed account with userId: $userId")
    }

    private suspend fun refreshAvailableAccounts() {
        tokenStorage.listStoredAccounts().fold(
            ifLeft = { error ->
                logger.warn("Failed to refresh available accounts: ${error.message}")
            },
            ifRight = { accounts ->
                _availableAccounts.value = accounts
                logger.info("Refreshed available accounts")
            }
        )
    }
}
