package eu.torvian.chatbot.app.service.turnnotification

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Process-wide conduit for turn-notification clicks.
 *
 * The notification's pending intent is delivered to the hosting activity, which knows nothing about
 * the dispatcher; this bus carries the session id the rest of the way. A buffered channel is used
 * rather than a shared flow so a click that arrives before the app shell is composed (a cold start
 * from the notification) is still delivered once the dispatcher starts collecting.
 */
object AndroidTurnNotificationClickBus {

    private val clicks = Channel<Long>(capacity = Channel.BUFFERED)

    /** Session ids of clicked notifications, delivered in click order. */
    val sessionIds: Flow<Long> = clicks.receiveAsFlow()

    /**
     * Publishes the session a delivered notification click referred to and consumes it.
     *
     * The click is consumed so it is delivered exactly once: the activity's launch intent outlives a
     * configuration change, and an extra left in place would be reported as a fresh click on every
     * recreation.
     *
     * @param intent Intent the activity was created or re-delivered with; ignored when it carries no
     *           session id, which is the case for plain task raises.
     */
    fun publishFromIntent(intent: Intent?) {
        AndroidTurnNotificationIntent.consumeSessionId(intent)?.let { clicks.trySend(it) }
    }
}
