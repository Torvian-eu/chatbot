package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.service.turnnotification.TurnOsChannelState
import eu.torvian.chatbot.app.service.turnnotification.TurnOsNotificationPermission
import eu.torvian.chatbot.app.service.turnnotification.TurnOsNotificationService
import eu.torvian.chatbot.app.service.turnnotification.osChannelState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * ViewModel for the "Notifications" settings category.
 *
 * Exposes the effective turn-alert toggles (the stored preference, or the enabled defaults when the
 * user never configured the feature) and classifies the OS-notification channel from those toggles
 * and the platform permission, so the settings surface renders a meaning rather than combining raw
 * inputs itself. Every change is persisted as one GLOBAL preference row.
 *
 * The permission is read when the category is opened and whenever the app regains the user's
 * attention, because an answer to a system prompt or a change in the OS or browser settings happens
 * outside this screen; a read never prompts. While the master toggle or the OS channel is off the
 * platform is not read at all, because the classification is the same whatever it would answer and
 * the probe is not free on every platform.
 *
 * Turning the OS-notification toggle on and the explicit request action are the only two moments
 * permission is asked for, because both are user gestures and Android and the browser require one;
 * a prompt is never raised from the alert path or on simply opening the category.
 *
 * @property userPreferenceRepository Repository backing the global preference row.
 * @property osNotifications Platform notification service used for permission queries and requests.
 * @property notificationService Service used to surface save failures to the user.
 * @property uiDispatcher Dispatcher used for UI-bound coroutines. Defaults to [Dispatchers.Main].
 */
class NotificationsViewModel(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val osNotifications: TurnOsNotificationService,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    /**
     * Effective toggles shown and edited by the tab. A `null` repository value means no stored row,
     * so the enabled defaults are shown rather than a disabled state.
     *
     * The initial value is taken from the repository snapshot so the first frame already reflects a
     * stored preference instead of flashing the enabled defaults before the first emission.
     */
    val preference: StateFlow<TurnNotificationPreference> = userPreferenceRepository
        .turnNotificationPreference
        .map { it ?: TurnNotificationPreference.DEFAULT }
        .stateIn(
            // The ViewModel's own job on the injected dispatcher, so the exposed state is driven by
            // the same dispatcher as every other coroutine in this class.
            scope = viewModelScope + uiDispatcher,
            started = SharingStarted.WhileSubscribed(),
            // A repository that has not loaded yet still falls back to the defaults the feature
            // applies, so no verdict is invented either way.
            initialValue = userPreferenceRepository.turnNotificationPreference.value
                ?: TurnNotificationPreference.DEFAULT
        )

    private val permissionState = MutableStateFlow<TurnOsNotificationPermission?>(null)

    /**
     * How the OS-notification channel currently stands, stored toggles combined with the platform
     * permission, or `null` while a wanted channel's permission has not been read yet.
     *
     * The unknown value is what keeps the surface from reporting a permission verdict it does not
     * have: a channel the user turned off is known to be [TurnOsChannelState.DISABLED] without any
     * read, and only a wanted channel needs a permission before it can be classified.
     */
    val osChannel: StateFlow<TurnOsChannelState?> = combine(preference, permissionState) { toggles, permission ->
        when {
            !toggles.enabled || !toggles.osNotificationEnabled -> TurnOsChannelState.DISABLED
            permission == null -> null
            else -> toggles.osChannelState(permission)
        }
    }.stateIn(
        // See [preference]: same job, injected dispatcher.
        // Nothing is known about the permission yet, so no channel state may be claimed.
        scope = viewModelScope + uiDispatcher,
        started = SharingStarted.WhileSubscribed(),
        initialValue = null
    )

    private val _saving = MutableStateFlow(false)

    /** Whether a preference write is currently in flight. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Refreshes the platform permission without prompting the user.
     *
     * Called when the category is (re-)selected and when the app regains the user's attention, so the
     * classification reflects changes made outside the app. A channel the user has not asked for is
     * not probed: it is classified as disabled from the toggles alone, and on desktop the probe
     * installs the notification tray icon as a side effect.
     */
    fun loadPermissionState() {
        viewModelScope.launch(uiDispatcher) {
            val toggles = effectivePreference()
            if (!toggles.enabled || !toggles.osNotificationEnabled) return@launch
            refreshPermissionState()
        }
    }

    /**
     * Reads the platform permission, never prompting.
     *
     * The stored preference is left untouched: it records what the user asked for, while the
     * permission only decides how the channel is classified.
     */
    private suspend fun refreshPermissionState() {
        permissionState.value = osNotifications.permissionState()
    }

    /**
     * Sets the master toggle, which suppresses both channels when turned off.
     *
     * Turning it on can make the OS channel wanted for the first time, so its permission is read
     * then; the platform is still never probed while the feature stays off.
     *
     * @param enabled Whether turn alerts should be produced at all.
     */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch(uiDispatcher) {
            val next = effectivePreference().copy(enabled = enabled)
            persistNow(next)
            if (enabled && next.osNotificationEnabled) refreshPermissionState()
        }
    }

    /**
     * Sets whether the bundled alert sound may play.
     *
     * @param enabled Whether the sound channel is available.
     */
    fun setSoundEnabled(enabled: Boolean) {
        persist { it.copy(soundEnabled = enabled) }
    }

    /**
     * Sets whether OS notifications may be shown, requesting platform permission when turned on.
     *
     * The stored flag records the user's intent even when the platform refuses, and the tab keeps
     * showing the channel as off until permission exists, so the two can never disagree.
     *
     * @param enabled Whether the OS notification channel is available.
     */
    fun setOsNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch(uiDispatcher) {
            // Requesting here rather than on the first alert keeps the prompt tied to a user gesture,
            // which Android and the browser both require.
            if (enabled) {
                permissionState.value = osNotifications.requestPermission()
            }
            persistNow(effectivePreference().copy(osNotificationEnabled = enabled))
        }
    }

    /**
     * Asks the platform for OS-notification permission, adopting the answer as the current state.
     *
     * Called only from a user gesture in the settings surface; nothing else may prompt, and the OS
     * channel stays silent until permission is granted regardless.
     */
    fun requestPermission() {
        viewModelScope.launch(uiDispatcher) {
            permissionState.value = osNotifications.requestPermission()
        }
    }

    /**
     * Applies a toggle change and persists the whole preference object.
     *
     * @param transform Change applied to the currently effective toggles.
     */
    private fun persist(transform: (TurnNotificationPreference) -> TurnNotificationPreference) {
        viewModelScope.launch(uiDispatcher) {
            persistNow(transform(effectivePreference()))
        }
    }

    /**
     * Reads the toggles to base a change on, with the defaults applied when nothing is stored.
     *
     * Reads the repository directly rather than the mapped [preference] flow so a change never
     * depends on whether a collector is currently subscribed to that flow.
     *
     * @return The effective toggles to copy a change from.
     */
    private fun effectivePreference(): TurnNotificationPreference =
        userPreferenceRepository.turnNotificationPreference.value ?: TurnNotificationPreference.DEFAULT

    /**
     * Writes [next] to the server and surfaces a failure in-app.
     *
     * @param next Toggles to persist.
     */
    private suspend fun persistNow(next: TurnNotificationPreference) {
        _saving.value = true
        userPreferenceRepository.setTurnNotificationPreference(next)
            .onLeft { error ->
                // The toggle reverts on the next preference sync, so no local rollback is needed.
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to save notification settings"
                )
            }
        _saving.value = false
    }
}
