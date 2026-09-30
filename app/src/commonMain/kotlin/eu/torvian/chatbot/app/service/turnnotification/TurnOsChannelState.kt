package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference

/**
 * How the OS-notification channel stands for a user's stored toggles on the current platform.
 *
 * Deriving this in one place keeps "can this channel notify right now" a single rule: the dispatcher
 * decides from it whether to show a notification, and the settings surface renders it instead of
 * combining the stored toggles with the platform permission itself.
 */
enum class TurnOsChannelState {
    /** The user wants OS notifications and the platform permits them. */
    ACTIVE,

    /** Permission has not been granted yet and the platform can still be asked. */
    NEEDS_PERMISSION,

    /** Permission was refused, but the platform may still be asked again from inside the app. */
    BLOCKED,

    /**
     * Notifications are switched off where no in-app prompt can reach them, so only a setting in the
     * system or browser settings can turn the channel on.
     */
    BLOCKED_BY_SETTINGS,

    /** The platform has no notification facility at all. */
    UNSUPPORTED,

    /** The user turned the OS channel off, so its permission does not matter. */
    DISABLED;

    /** Whether asking the platform again can still change the outcome. */
    val canBeRequested: Boolean
        get() = this == NEEDS_PERMISSION || this == BLOCKED
}

/**
 * Classifies the OS-notification channel for these toggles and the observed platform permission.
 *
 * The permission is classified with an exhaustive `when` so a newly added permission value fails to
 * compile here, rather than silently reading as requestable.
 *
 * @receiver Stored toggles of the user.
 * @param permission Platform permission as last observed.
 * @return The channel state the alert dispatcher and the settings surface both act on.
 */
fun TurnNotificationPreference.osChannelState(
    permission: TurnOsNotificationPermission
): TurnOsChannelState {
    // A channel the user turned off needs no permission at all, so it is classified without one.
    if (!osNotificationEnabled) return TurnOsChannelState.DISABLED
    return when (permission) {
        TurnOsNotificationPermission.GRANTED -> TurnOsChannelState.ACTIVE
        TurnOsNotificationPermission.NOT_DETERMINED -> TurnOsChannelState.NEEDS_PERMISSION
        TurnOsNotificationPermission.DENIED -> TurnOsChannelState.BLOCKED
        TurnOsNotificationPermission.REQUIRES_SETTINGS -> TurnOsChannelState.BLOCKED_BY_SETTINGS
        TurnOsNotificationPermission.UNSUPPORTED -> TurnOsChannelState.UNSUPPORTED
    }
}
