package eu.torvian.chatbot.app.service.turnnotification

import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.w3c.notifications.DENIED
import org.w3c.notifications.GRANTED
import org.w3c.notifications.Notification
import org.w3c.notifications.NotificationOptions
import org.w3c.notifications.NotificationPermission
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Web [TurnOsNotificationService] built on the browser Notification API.
 *
 * Browser support varies: desktop browsers implement the API, some mobile browsers refuse the
 * constructor without a service worker, and the permission may be denied or blocked. Every call is
 * therefore wrapped, so an unsupported browser or a refusal degrades to sound-only without affecting
 * the turn.
 */
class TurnOsNotificationServiceWasmJs : TurnOsNotificationService {

    private companion object {
        val logger = kmpLogger<TurnOsNotificationServiceWasmJs>()
    }

    private val mutableClickedSessionIds = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    override val clickedSessionIds: Flow<Long> = mutableClickedSessionIds.asSharedFlow()

    override suspend fun permissionState(): TurnOsNotificationPermission = readPermissionState()

    @OptIn(ExperimentalWasmJsInterop::class)
    override suspend fun requestPermission(): TurnOsNotificationPermission {
        // The browser only publishes the user's answer through the promise the request returns;
        // awaiting it lets the settings surface adopt the granted state at once instead of re-reading
        // the permission while the prompt is still open.
        runCatching { Notification.requestPermission().await() }.onFailure { failure ->
            logger.warn("Failed to request notification permission: ${failure.message}", failure)
        }
        // Re-read rather than mapping the promise value so the reported state matches what the
        // browser actually exposes, including a grant that arrived outside the answered prompt.
        return readPermissionState()
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    override suspend fun showNotification(request: TurnOsNotificationRequest) {
        runCatching {
            val options = NotificationOptions().apply { body = request.body }
            val notification = Notification(request.title, options)
            // Each notification carries the session it was raised for, so a click on it selects that
            // session.
            notification.onclick = {
                mutableClickedSessionIds.tryEmit(request.sessionId)
                window.focus()
            }
        }.onFailure { failure ->
            logger.warn("Failed to show a turn notification: ${failure.message}", failure)
        }
    }

    override suspend fun bringAppToFront() {
        runCatching { window.focus() }.onFailure { failure ->
            logger.warn("Failed to focus the browser tab: ${failure.message}", failure)
        }
    }

    /**
     * Maps the browser's permission value onto the shared enum.
     *
     * An inaccessible `Notification` global (a browser without the API) is reported as
     * [TurnOsNotificationPermission.UNSUPPORTED] rather than prompting for a permission that could
     * never be granted.
     *
     * @return The permission state to gate the OS channel on.
     */
    @OptIn(ExperimentalWasmJsInterop::class)
    private fun readPermissionState(): TurnOsNotificationPermission = runCatching {
        when (Notification.permission) {
            NotificationPermission.GRANTED -> TurnOsNotificationPermission.GRANTED
            NotificationPermission.DENIED -> TurnOsNotificationPermission.DENIED
            else -> TurnOsNotificationPermission.NOT_DETERMINED
        }
    }.getOrElse { failure ->
        logger.warn("Browser notifications are unavailable: ${failure.message}", failure)
        TurnOsNotificationPermission.UNSUPPORTED
    }
}
