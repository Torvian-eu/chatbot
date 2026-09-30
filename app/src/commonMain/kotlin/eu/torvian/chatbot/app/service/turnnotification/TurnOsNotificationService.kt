package eu.torvian.chatbot.app.service.turnnotification

import kotlinx.coroutines.flow.Flow

/**
 * Whether the platform can currently show an OS notification for a turn alert.
 *
 * The distinction between the negative states matters to the settings UI only: all of them mean the
 * dispatcher skips the OS channel and keeps the sound, while [NOT_DETERMINED] and [DENIED] differ
 * from [REQUIRES_SETTINGS] in whether an in-app request can still turn them into [GRANTED].
 */
enum class TurnOsNotificationPermission {
    /** The platform has no notification API at all (for example a headless desktop session). */
    UNSUPPORTED,

    /** The platform supports notifications but the user has not decided yet. */
    NOT_DETERMINED,

    /** The user granted permission; notifications may be shown. */
    GRANTED,

    /**
     * A request was refused, but the platform may still be asked again, so an in-app request can
     * still change the outcome.
     */
    DENIED,

    /**
     * Notifications are switched off somewhere an in-app prompt cannot reach, so only the system or
     * browser settings can change the outcome (for example the app-wide notification switch of an
     * Android version without a runtime permission).
     */
    REQUIRES_SETTINGS
}

/**
 * One OS notification to display for a turn alert.
 *
 * @property sessionId Session the alert belongs to; also used by platforms that raise the app and
 *           select this session when the notification is clicked.
 * @property title Headline naming the session.
 * @property body State description of the alert.
 */
data class TurnOsNotificationRequest(
    val sessionId: Long,
    val title: String,
    val body: String
)

/**
 * Shows turn alerts through the operating system's notification facility.
 *
 * Every method is best effort: implementations catch and log their platform's failures and return
 * normally, so an alerting problem can never fail or cancel the turn that produced the trigger.
 */
interface TurnOsNotificationService {

    /**
     * Reports the platform's current capability without prompting the user.
     *
     * @return The permission state to gate the OS channel on.
     */
    suspend fun permissionState(): TurnOsNotificationPermission

    /**
     * Asks the platform for notification permission, where it supports being asked.
     *
     * Must be called while handling a user gesture on platforms that require one (Android, Web).
     * Suspends until the platform reports the answer where it exposes one.
     *
     * @return The permission state observed after the request, so the settings surface can converge
     *         on the answer the user gave rather than a state read before it was decided.
     */
    suspend fun requestPermission(): TurnOsNotificationPermission

    /**
     * Displays one notification, or does nothing when the platform cannot currently show it.
     *
     * @param request Notification content and the session to select when it is clicked.
     */
    suspend fun showNotification(request: TurnOsNotificationRequest)

    /**
     * Raises and focuses the application window or tab, best effort.
     *
     * Used when a notification is clicked so the user lands back in the app; platforms whose
     * notification click already raises the app may leave this as a no-op.
     */
    suspend fun bringAppToFront()

    /**
     * Session ids reported by clicked notifications, in click order.
     *
     * The dispatcher selects the session and raises the app; an empty flow on platforms without a
     * usable click callback keeps the alert usable as a mere sound/visual cue.
     */
    val clickedSessionIds: Flow<Long>
}
