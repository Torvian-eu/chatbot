package eu.torvian.chatbot.app.service.turnnotification

import android.content.Context
import android.content.Intent

/**
 * Builds and reads the intents used by turn-status notifications.
 *
 * Click intents carry the triggering session id so the app can select it, while raise intents only
 * bring an existing task forward. Keeping the two apart is what prevents a raise from being
 * reported back as a fresh click.
 */
object AndroidTurnNotificationIntent {

    /** Intent extra carrying the session a notification was raised for. */
    const val EXTRA_SESSION_ID: String = "eu.torvian.chatbot.app.turn_notification.session_id"

    /**
     * Prefix of the per-session action used to keep click intents distinct.
     *
     * Pending intents whose intents differ only in extras compare equal, so the action must vary by
     * session; without it a click would deliver a stale session id.
     */
    private const val ACTION_CLICK_PREFIX: String = "eu.torvian.chatbot.app.turn_notification.click"

    /** Sentinel stored when no session id is present, distinguishing it from session id `0`. */
    private const val NO_SESSION_ID: Long = -1L

    /**
     * Builds the intent a notification click should deliver.
     *
     * Uses the package launch intent so the existing task is brought forward rather than a second
     * instance being created.
     *
     * @param context Context used to resolve the launcher activity.
     * @param sessionId Session to select when the intent is handled.
     * @return The click intent, or `null` when the package has no launcher activity.
     */
    fun buildClickIntent(context: Context, sessionId: Long): Intent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return intent.apply {
            action = "$ACTION_CLICK_PREFIX.$sessionId"
            putExtra(EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    /**
     * Builds the intent used to raise the app without a session selection.
     *
     * Carries no session extra on purpose: a raise triggered while handling a click must not be
     * reported as a second click.
     *
     * @param context Context used to resolve the launcher activity.
     * @return The raise intent, or `null` when the package has no launcher activity.
     */
    fun buildRaiseIntent(context: Context): Intent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    /**
     * Reads the session id a notification click carried.
     *
     * @param intent Intent the activity was created or re-delivered with, if any.
     * @return The session to select, or `null` when the intent carries none.
     */
    fun extractSessionId(intent: Intent?): Long? {
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, NO_SESSION_ID) ?: NO_SESSION_ID
        return sessionId.takeIf { it != NO_SESSION_ID }
    }

    /**
     * Reads a notification click's session id and clears it from the intent.
     *
     * Consuming the click is what makes it one-shot: the activity's launch intent survives a
     * configuration change, so an extra left in place would be re-published on every recreation and
     * re-select the session the user clicked once.
     *
     * @param intent Intent the activity was created or re-delivered with, if any.
     * @return The session to select, or `null` when the intent carries no click.
     */
    fun consumeSessionId(intent: Intent?): Long? {
        if (intent == null) return null
        val sessionId = extractSessionId(intent) ?: return null
        intent.removeExtra(EXTRA_SESSION_ID)
        // The per-session action only exists to keep the pending intents distinct; once the click is
        // handled, clearing it keeps a re-delivered intent from looking like a fresh click.
        if (intent.action?.startsWith(ACTION_CLICK_PREFIX) == true) {
            intent.action = null
        }
        return sessionId
    }

    /**
     * Derives a stable request code for a session's click pending intent.
     *
     * @param sessionId Session the notification belongs to.
     * @return Request code distinguishing this session from other sessions.
     */
    fun requestCode(sessionId: Long): Int = (sessionId xor (sessionId ushr 32)).toInt()

    /**
     * Derives the notification id used for a session.
     *
     * One id per session is deliberate: parallel sessions then coexist without overwriting each
     * other's notification, while repeated alerts for the same session replace each other so a session
     * never stacks reminders. A later alert for one session cannot strand information the user still
     * needs, because an alert carries only the session name and the state that raised it — the state
     * that can follow another is the resolution of a turn already reported as waiting, and a repeat of
     * the same state is a reminder with the identical click target.
     *
     * @param sessionId Session the notification belongs to.
     * @return Notification id unique within the app's notifications.
     */
    fun notificationId(sessionId: Long): Int = requestCode(sessionId)
}
