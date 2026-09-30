package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.service.turnnotification.TurnOsChannelState
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference

/**
 * State contract for the Notifications tab.
 *
 * @property preference Effective turn-alert toggles currently stored for the user.
 * @property osChannel How the OS-notification channel currently stands, already classified from the
 *           stored toggle and the platform permission, so the tab only renders it. `null` means the
 *           permission has not been read yet, which the tab shows as no verdict at all rather than
 *           guessing one.
 * @property saving Whether a toggle change is currently being persisted.
 */
data class NotificationsTabState(
    val preference: TurnNotificationPreference,
    val osChannel: TurnOsChannelState?,
    val saving: Boolean
)
