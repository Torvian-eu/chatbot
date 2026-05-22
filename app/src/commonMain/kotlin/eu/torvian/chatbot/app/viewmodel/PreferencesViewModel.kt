package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing user appearance preferences.
 *
 * This ViewModel provides a clean separation between the UI layer and the repository,
 * exposing reactive state for theme and scope preferences.
 *
 * @property userPreferenceRepository The repository for user preference operations.
 * @property notificationService Service for showing notifications and handling errors.
 *
 * @property isDeviceScoped Whether the preference should be stored device-scoped.
 * When true, preferences apply only to this device. When false, preferences apply globally.
 */
class PreferencesViewModel(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val notificationService: NotificationService
) : ViewModel() {

    private val _isDeviceScoped: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isDeviceScoped: StateFlow<Boolean> = _isDeviceScoped.asStateFlow()

    /**
     * Reactive stream of detailed preferences showing both global and device-specific values.
     */
    val detailedPreferences: StateFlow<Map<String, PreferenceDetailDTO>> =
        userPreferenceRepository.detailedPreferences

    /**
     * Computed property that returns the current scope based on [_isDeviceScoped].
     */
    private val currentScope: PreferenceScope
        get() = if (_isDeviceScoped.value) PreferenceScope.DEVICE else PreferenceScope.GLOBAL

    /**
     * Updates the user's theme preference.
     *
     * - A non-null [theme] sets the theme preference.
     * - `null` removes the theme preference, falling back to system default.
     *
     * The scope is determined by the current value of [_isDeviceScoped].
     *
     * @param theme The desired theme string value (e.g., "dark", "light"), or `null` to clear it.
     */
    fun setTheme(theme: String?) {
        viewModelScope.launch {
            userPreferenceRepository.setTheme(theme, currentScope)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(error, "Failed to set theme preference")
                    },
                    ifRight = {
                        // Theme updated successfully
                    }
                )
        }
    }

    /**
     * Syncs detailed preferences from the server.
     *
     * This should be called when the Settings UI is displayed to show the inheritance chain.
     */
    fun syncDetailedPreferences() {
        viewModelScope.launch {
            userPreferenceRepository.syncDetailedPreferences().fold(
                ifLeft = { error ->
                    notificationService.repositoryError(error, "Failed to sync detailed preferences")
                },
                ifRight = {
                    // Detailed preferences synced successfully
                }
            )
        }
    }

    /**
     * Updates the device scope state.
     *
     * When [isDeviceScoped] is true, preferences will be stored device-scoped.
     * When false, preferences will be stored globally.
     *
     * @param isDeviceScoped Whether the preference should be stored device-scoped.
     */
    fun setDeviceScoped(isDeviceScoped: Boolean) {
        _isDeviceScoped.value = isDeviceScoped
    }
}
