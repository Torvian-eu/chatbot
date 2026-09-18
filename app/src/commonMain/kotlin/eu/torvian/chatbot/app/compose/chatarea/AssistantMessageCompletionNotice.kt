package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.assistant_message_failed_label
import eu.torvian.chatbot.app.generated.resources.assistant_message_stopped_by_user
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the reason why an assistant message was not completed, inside that message's bubble.
 *
 * Only terminal, explained non-completions are rendered (see
 * [ChatMessage.AssistantMessage.showsIncompleteNotice]): a completed message and the in-flight
 * placeholder of the active turn stay silent, so no turn-state plumbing is needed to hide the notice
 * while the current answer is still streaming.
 *
 * The wording follows the persisted cause. A user interruption carries no server text, so a localized
 * label is derived from the cause alone; a failure shows the localized label followed by the reason
 * recorded by the server, which is intentionally rendered as-is (it is already bounded and written in
 * the server's language, and it must never be replaced by the partial content).
 *
 * @param message Assistant message whose terminal state should be explained when it is incomplete.
 * @param contentColor Base color of the surrounding bubble content, used for the notice text so it
 *        stays legible for the role that owns the bubble.
 * @param modifier Modifier applied to the notice row.
 */
@Composable
internal fun AssistantMessageCompletionNotice(
    message: ChatMessage.AssistantMessage,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val label = when (message.incompleteCause) {
        // The reason text is deliberately absent for a user stop: the client localizes the label from
        // the cause instead of persisting a message for it.
        AssistantMessageIncompleteCause.INTERRUPTED_BY_USER ->
            stringResource(Res.string.assistant_message_stopped_by_user)

        // The failure reason is server-authored and rendered verbatim; only the label is localized.
        // `listOfNotNull` keeps the label alone when no reason was recorded for the failure.
        AssistantMessageIncompleteCause.FAILED -> listOfNotNull(
            stringResource(Res.string.assistant_message_failed_label),
            message.errorMessage
        ).joinToString(": ")

        // In-flight placeholder or completed message: nothing to explain.
        null -> return
    }

    Row(
        modifier = modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            // The icon carries the "did not complete" accent; the text stays in the bubble's content
            // color so the reason reads as secondary information rather than as content.
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.8f)
        )
    }
}
