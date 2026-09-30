package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.service.turnnotification.TurnOsChannelState

/**
 * Notifications settings tab: the master toggle for turn alerts plus the independent sound and
 * OS-notification channels, and a notice describing how the OS channel currently stands.
 *
 * All three settings live in one server-side GLOBAL preference row, so every change persists
 * immediately and applies to the running session without a save button.
 *
 * The OS-notification switch shows whether the channel can actually notify, and the notice carries
 * the permission request, so the words for a channel state live here while the decision of which
 * state applies comes from the ViewModel. A channel state that is not known yet renders neither the
 * notice nor an active switch, so no permission verdict is shown before one has been read.
 * Android 13+ and browsers only grant on a user gesture, so
 * the request is never triggered from anywhere but these controls.
 *
 * @param state The current tab state.
 * @param actions The action callbacks for the tab.
 * @param modifier Modifier applied to the tab.
 */
@Composable
fun NotificationsTab(
    state: NotificationsTabState,
    actions: NotificationsTabActions,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Turn alerts",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            ToggleItem(
                headline = "Notify about finished turns",
                supporting = "Signal when a turn finishes or waits for your approval while the app " +
                    "is not focused: the alert sound, plus a system notification where notifications " +
                    "are allowed.",
                checked = state.preference.enabled,
                enabled = !state.saving,
                onCheckedChange = actions::onSetEnabled
            )
        }

        item {
            ToggleItem(
                headline = "Sound",
                supporting = "Play the bundled notification sound.",
                checked = state.preference.soundEnabled,
                // The channels only matter while the master toggle is on; disabling them prevents a
                // change that the dispatcher would ignore anyway.
                enabled = !state.saving && state.preference.enabled,
                onCheckedChange = actions::onSetSoundEnabled
            )
        }

        item {
            ToggleItem(
                headline = "System notification",
                supporting = "Show a notification through your operating system.",
                checked = state.osChannel == TurnOsChannelState.ACTIVE,
                enabled = !state.saving && state.preference.enabled,
                onCheckedChange = actions::onSetOsNotificationEnabled
            )
        }

        // Nothing to explain while alerts are off entirely (the rows above are disabled anyway) or
        // while the user deliberately keeps the OS channel off: the switch already shows that. An
        // unread permission is not a verdict either, so it explains nothing yet.
        val channel = state.osChannel
        if (state.preference.enabled && channel != null && channel != TurnOsChannelState.DISABLED) {
            item {
                OsChannelNotice(
                    channel = channel,
                    onRequestPermission = actions::onRequestPermission,
                    enabled = !state.saving
                )
            }
        }
    }
}

/**
 * Renders one labelled toggle row.
 *
 * @param headline Main label of the setting.
 * @param supporting Short description shown under the label.
 * @param checked Whether the setting is on.
 * @param enabled Whether the row accepts input.
 * @param onCheckedChange Receives the requested new value.
 * @param modifier Modifier applied to the row.
 */
@Composable
private fun ToggleItem(
    headline: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = modifier
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .fillMaxWidth()
    )
}

/**
 * Renders how the OS-notification channel stands, with the request action while it can still be
 * granted.
 *
 * The notice is emphasized whenever the channel cannot notify, because the feature otherwise looks
 * configured while it stays silent; the request itself needs a gesture the user has to make here.
 *
 * @param channel Channel state to describe.
 * @param onRequestPermission Invoked when the user asks for notification permission.
 * @param enabled Whether the request action currently accepts input.
 * @param modifier Modifier applied to the notice surface.
 */
@Composable
private fun OsChannelNotice(
    channel: TurnOsChannelState,
    onRequestPermission: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val blocked = channel != TurnOsChannelState.ACTIVE
    val contentColor = if (blocked) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = if (blocked) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = channel.headline(),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
            Text(
                text = channel.explanation(),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            if (channel.canBeRequested) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRequestPermission, enabled = enabled) {
                        Text(channel.requestActionLabel())
                    }
                }
            }
        }
    }
}

/**
 * Headline naming the channel state.
 *
 * @receiver Channel state to name.
 * @return Sentence telling the user what stands between them and OS notifications.
 */
private fun TurnOsChannelState.headline(): String = when (this) {
    TurnOsChannelState.ACTIVE -> "System notifications are on"
    TurnOsChannelState.NEEDS_PERMISSION -> "Allow system notifications"
    TurnOsChannelState.BLOCKED -> "System notifications are blocked"
    TurnOsChannelState.BLOCKED_BY_SETTINGS -> "System notifications are turned off in the system settings"
    TurnOsChannelState.UNSUPPORTED -> "This platform cannot show notifications"
    TurnOsChannelState.DISABLED -> "System notifications are turned off"
}

/**
 * Label of the button that asks the platform for notification permission.
 *
 * @receiver Channel state that can still be requested.
 * @return Button label naming the outcome the user is asking for.
 */
private fun TurnOsChannelState.requestActionLabel(): String = when (this) {
    TurnOsChannelState.BLOCKED -> "Request permission again"
    else -> "Allow notifications"
}

/**
 * Explains what a channel state means for the user.
 *
 * @receiver Channel state to explain.
 * @return Sentence describing the resulting behaviour, including the sound-only fallback.
 */
private fun TurnOsChannelState.explanation(): String = when (this) {
    TurnOsChannelState.ACTIVE ->
        "Notifications are shown while the app is not focused."

    TurnOsChannelState.NEEDS_PERMISSION ->
        "Notifications are only shown once the operating system or browser is allowed to show " +
            "them; until then the sound is still played."

    TurnOsChannelState.BLOCKED ->
        "Permission was refused, so no notifications are shown; the sound is still played. You may " +
            "need to allow them in your system or browser settings."

    TurnOsChannelState.BLOCKED_BY_SETTINGS ->
        "Notifications are turned off outside the app, and nothing here can ask for them; turn them " +
            "on in your system settings. The sound is still played."

    TurnOsChannelState.UNSUPPORTED ->
        "This platform cannot show notifications; the sound is still played."

    TurnOsChannelState.DISABLED ->
        "The channel is turned off, so no notification is shown for a turn alert."
}

