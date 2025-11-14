package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus

/**
 * Displays a clickable badge for a tool call, showing status and execution time.
 */
@Composable
fun ToolCallBadge(
    toolCall: ToolCall,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when (toolCall.status) {
        ToolCallStatus.PENDING -> Pair(
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.tertiary
        )

        ToolCallStatus.SUCCESS -> Pair(
            Icons.Default.Check,
            MaterialTheme.colorScheme.primary
        )

        ToolCallStatus.ERROR -> Pair(
            Icons.Default.Error,
            MaterialTheme.colorScheme.error
        )
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = toolCall.toolName,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )

            // Show execution time if available
            toolCall.durationMs?.let { duration ->
                Text(
                    text = "(${duration}ms)",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Displays a horizontal row of tool call badges.
 * Wraps to multiple lines if needed.
 */
@Composable
fun ToolCallBadges(
    toolCalls: List<ToolCall>,
    onToolCallClick: (ToolCall) -> Unit,
    modifier: Modifier = Modifier
) {
    if (toolCalls.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = "Tools Used:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(4.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            toolCalls.forEach { toolCall ->
                ToolCallBadge(
                    toolCall = toolCall,
                    onClick = { onToolCallClick(toolCall) }
                )
            }
        }
    }
}

