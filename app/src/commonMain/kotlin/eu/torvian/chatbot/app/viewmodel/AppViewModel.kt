package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import kotlinx.coroutines.flow.*

/**
 * ViewModel for application-level state, including theme preferences.
 *
 * This ViewModel provides a clean separation between the UI layer and the repository,
 * exposing computed state flows that the UI can observe without direct repository access.
 *
 * It also reacts to authentication changes so preference synchronization happens as soon
 * as a user session becomes available.
 *
 * @property authRepository The authentication repository that exposes login state changes.
 * @property userPreferenceRepository The user preference repository for fetching and updating preferences.
 * @property notificationService Service for showing notifications and handling errors.
 */
class AppViewModel(
    private val authRepository: AuthRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val notificationService: NotificationService
) : ViewModel() {

    init {
        authRepository.authState.filterIsInstance<AuthState.Authenticated>()
            .distinctUntilChangedBy { it.userId }
            .onEach {
                userPreferenceRepository.syncPreferences().fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(error, "Failed to sync preferences on login")
                    }, ifRight = {
                        // Preferences synced successfully; no further action needed here.
                    })
            }
            .launchIn(viewModelScope)
    }

    /**
     * Reactive stream of the user's theme preference as a string.
     *
     * - `"dark"`  -> force dark theme
     * - `"light"` -> force light theme
     * - `null`    -> follow the system setting (no preference set)
     */
    val currentTheme: StateFlow<String?> = userPreferenceRepository.theme
}
