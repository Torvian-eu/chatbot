package eu.torvian.chatbot.app.service.turnnotification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.flow.Flow

/**
 * Android [TurnOsNotificationService] built on the framework notification APIs.
 *
 * Notifications are posted on a dedicated channel and reuse the app's launcher intent so a click
 * brings the existing task forward and selects the triggering session. Permission is checked at
 * runtime where the platform demands it and requested through the activity's launcher, so the state
 * reported after a request is the state the user's answer produced; where the platform offers no
 * prompt at all, the state says so and the settings surface points at the system settings rather than
 * offering a request. Every failure is logged and swallowed so the alert path can never affect the
 * turn.
 *
 * @property context Context used to reach the notification manager, the package manager, and (when
 *           it is an activity) the rationale flag behind the permission state.
 */
class TurnOsNotificationServiceAndroid(
    private val context: Context
) : TurnOsNotificationService {

    private companion object {
        /** Channel the turn alerts are posted on. */
        const val CHANNEL_ID = "turn_status"

        val logger = kmpLogger<TurnOsNotificationServiceAndroid>()
    }

    override val clickedSessionIds: Flow<Long> = AndroidTurnNotificationClickBus.sessionIds

    override suspend fun permissionState(): TurnOsNotificationPermission {
        if (isPermissionGranted()) return TurnOsNotificationPermission.GRANTED
        // Below Android 13 notifications are controlled by an app-wide system setting that no prompt
        // can change, so the user is directed to the system settings rather than being offered a
        // request that cannot work.
        if (!supportsRuntimePermission()) return TurnOsNotificationPermission.REQUIRES_SETTINGS
        // The framework cannot distinguish "never asked" from "permanently denied"; the rationale
        // flag is the closest signal, and both states still mean the channel is skipped. Without a
        // rationale the prompt is still worth trying, so the state stays requestable.
        return if (isRationaleRequested()) TurnOsNotificationPermission.DENIED else TurnOsNotificationPermission.NOT_DETERMINED
    }

    override suspend fun requestPermission(): TurnOsNotificationPermission {
        if (isPermissionGranted()) return TurnOsNotificationPermission.GRANTED
        // Below Android 13 notifications are controlled by a system setting that no prompt can change.
        if (!supportsRuntimePermission()) return permissionState()
        return requestRuntimePermission()
    }

    /**
     * Raises the Android 13+ notification-permission prompt and reports the state it produced.
     *
     * Separated and annotated so the platform permission constant, which only exists from API 33 while
     * this app also supports older versions, is referenced only where those versions cannot reach it.
     *
     * @return The permission state observed once the user answered, or the current state when no
     *         activity could raise the prompt.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun requestRuntimePermission(): TurnOsNotificationPermission {
        val answer = AndroidTurnNotificationPermissionBus.request(Manifest.permission.POST_NOTIFICATIONS)
        if (answer == null) {
            // Without an activity there is no gesture to attach the prompt to; the dispatcher then
            // keeps playing the sound instead.
            logger.warn("Cannot request notification permission: no hosting activity available")
            return permissionState()
        }
        // The framework's rationale flag is what separates a permanent refusal from "not decided
        // yet", so the state is read again instead of being derived from the boolean answer.
        val state = permissionState()
        // A refusal with no rationale left after a prompt this app just raised means the system will
        // not show the dialog again: only the app's notification settings can still change the answer.
        val result = if (!answer && state == TurnOsNotificationPermission.NOT_DETERMINED) {
            TurnOsNotificationPermission.REQUIRES_SETTINGS
        } else {
            state
        }
        logger.info("Notification permission prompt answered with granted=$answer; state is $result")
        return result
    }

    override suspend fun showNotification(request: TurnOsNotificationRequest) {
        runCatching {
            val manager = notificationManager() ?: return@runCatching
            ensureChannel(manager)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(request.title)
                .setContentText(request.body)
                .setAutoCancel(true)
                .apply {
                    buildClickPendingIntent(request.sessionId)?.let { setContentIntent(it) }
                }
                .build()
            // One notification per session: concurrent sessions stay side by side, while a later alert
            // for the same session takes the place of the earlier one.
            manager.notify(AndroidTurnNotificationIntent.notificationId(request.sessionId), notification)
        }.onFailure { failure ->
            logger.warn("Failed to show a turn notification: ${failure.message}", failure)
        }
    }

    override suspend fun bringAppToFront() {
        runCatching {
            // A raise carries no session extra, so re-raising can never be mistaken for another click.
            val raiseIntent = AndroidTurnNotificationIntent.buildRaiseIntent(context) ?: return@runCatching
            context.startActivity(raiseIntent)
        }.onFailure { failure ->
            logger.warn("Failed to raise the application: ${failure.message}", failure)
        }
    }

    /**
     * Reports whether notifications are currently allowed by the platform.
     *
     * @return `true` when a notification would be shown without prompting.
     */
    private fun isPermissionGranted(): Boolean = if (supportsRuntimePermission()) {
        isRuntimePermissionGranted()
    } else {
        // Below Android 13 the app-wide switch is the only gate and cannot be requested at runtime.
        notificationManager()?.areNotificationsEnabled() == true
    }

    /**
     * Reports whether this platform version gates notifications behind a runtime permission.
     *
     * @return `true` from Android 13 on, where the permission can be granted both in the app and in
     *         the system settings.
     */
    private fun supportsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Reads the notification permission itself.
     *
     * @return `true` when the runtime permission is granted.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isRuntimePermissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Reports whether the framework would show the permission rationale.
     *
     * @return `true` when a prompt was already refused at least once, which the framework reports as
     *         the rationale needing to be shown.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isRationaleRequested(): Boolean =
        findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true

    /**
     * Resolves the notification manager.
     *
     * @return The manager, or `null` when the platform service is unavailable.
     */
    private fun notificationManager(): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * Creates the alert channel once, as required from Android 8 on.
     *
     * @param manager Manager the channel is registered with.
     */
    private fun ensureChannel(manager: NotificationManager) {
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        // The app label is already localized, so the channel name needs no extra string resource.
        val channelName = context.applicationInfo.loadLabel(context.packageManager).toString()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * Builds the pending intent delivered when the notification is clicked.
     *
     * @param sessionId Session to select after the click.
     * @return The pending intent, or `null` when the package has no launcher activity.
     */
    private fun buildClickPendingIntent(sessionId: Long): PendingIntent? {
        val intent = AndroidTurnNotificationIntent.buildClickIntent(context, sessionId) ?: return null
        return PendingIntent.getActivity(
            context,
            AndroidTurnNotificationIntent.requestCode(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Finds the hosting activity behind this context, if any.
     *
     * Unwrapping the context chain is used instead of lifecycle bookkeeping because the dependency
     * is injected with the activity as its context during composition, and the prompt only ever
     * needs to happen from a user gesture on that activity.
     *
     * @return The hosting activity, or `null` when the context is application-scoped.
     */
    private fun findActivity(): ComponentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        return null
    }
}
