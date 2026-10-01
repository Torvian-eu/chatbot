package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.chat.reasoning.ReasoningDisplay
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.reasoning_display_truncated
import eu.torvian.chatbot.app.generated.resources.reasoning_encrypted_notice
import eu.torvian.chatbot.app.generated.resources.reasoning_section_label
import eu.torvian.chatbot.app.generated.resources.reasoning_summary_label
import eu.torvian.chatbot.app.generated.resources.reasoning_thinking_indicator
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the collapsible reasoning/thinking section of one assistant message.
 *
 * The section only carries the plaintext derived from the message's reasoning items (see [ReasoningDisplay]); raw
 * item JSON and encrypted payloads have no path into this composable. Its content is hidden until the user expands
 * it, so the reasoning never competes with the answer for attention.
 *
 * @param display Text blocks to show inside the section, including whether the header carries the thinking
 *        indicator.
 * @param isExpanded Whether the content is currently shown.
 * @param contentColor Base color of the surrounding bubble content.
 * @param onToggle Invoked when the user clicks the header row.
 * @param modifier Modifier applied to the section container.
 */
@Composable
internal fun AssistantMessageReasoningSection(
    display: ReasoningDisplay,
    isExpanded: Boolean,
    contentColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 6.dp)) {
        // The header row as a whole is the toggle target, so the tooltip and the click label both describe the whole
        // row; the chevron inside it stays decoration.
        val toggleActionText = if (isExpanded) "Hide reasoning" else "Show reasoning"
        PlainTooltipBox(text = toggleActionText, showDelay = 500L) {
            Row(
                // Wraps its content instead of filling the row so the chevron stays next to the label rather than
                // floating at the far edge of the bubble.
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = toggleActionText, onClick = onToggle)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.reasoning_section_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
                // Additive and static for as long as the generation is still reasoning: no animation, so the section
                // heading never moves on its own and never claims activity after the answer started.
                if (display.showsThinkingIndicator) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(Res.string.reasoning_thinking_indicator),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    // Unlabelled on purpose: the surrounding clickable row already announces the action, and a second
                    // label on the icon would have a screen reader read it twice.
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (isExpanded) {
            if (display.summaryText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.reasoning_summary_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = display.summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.9f)
                )
            }
            if (display.reasoningText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = display.reasoningText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
            if (display.isTruncated) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.reasoning_display_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f)
                )
            }
            // Shown alongside a summary when the provider delivered both: the summary is renderable, the
            // reasoning itself is not, so both facts are stated.
            if (display.showsEncryptedNotice) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.reasoning_encrypted_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}
