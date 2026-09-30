package eu.torvian.chatbot.app.compose.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import eu.torvian.chatbot.app.service.turnnotification.AppFocusState
import eu.torvian.chatbot.app.service.turnnotification.TurnNotificationDispatcher
import org.koin.compose.koinInject

/**
 * Headless composable that runs the out-of-app turn alerting for as long as the authenticated app
 * shell is composed.
 *
 * It has two jobs: mirror the window/tab focus into the shared [AppFocusState] so the dispatcher can
 * gate alerts on the user's attention, and drive the [TurnNotificationDispatcher] collectors. Both
 * live in `LaunchedEffect`s, so the alerts stop on logout and restart on login without application
 * scope bookkeeping, polling, or timers.
 *
 * @param onOpenSession Called with the triggering session id when a notification is clicked; the
 *           host selects that session and brings the chat screen forward.
 * @param dispatcher Alert dispatcher resolved from Koin.
 * @param focusState Shared focus state resolved from Koin.
 */
@Composable
fun TurnNotificationHost(
    onOpenSession: (Long) -> Unit,
    dispatcher: TurnNotificationDispatcher = koinInject(),
    focusState: AppFocusState = koinInject()
) {
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(windowInfo, focusState) {
        // snapshotFlow re-emits only on actual focus changes, so this stays idle while the user
        // works and never polls.
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            focusState.setFocused(focused)
        }
    }

    LaunchedEffect(dispatcher) {
        dispatcher.run(onOpenSession)
    }
}
