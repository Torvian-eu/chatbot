package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Horizontal offset applied to a sub-group header and its items, relative to the bucket header.
 *
 * Private so both Tools surfaces share one indent step: the dialog's chips and the detail page's text
 * cannot drift apart.
 */
private val SubGroupIndent = 16.dp

/**
 * Static, non-interactive header of one tool-origin bucket.
 *
 * Deliberately flush left and styled like the "Spawnable agent roles" group headings, so the origin
 * hierarchy reads the same as the other grouped pickers in the role form.
 *
 * @param title The bucket's display label.
 * @param modifier Modifier applied to the header text.
 */
@Composable
fun AgentToolBucketHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * One indented origin sub-group: its header followed by [content] (the group's chips or tool names).
 *
 * Both halves are indented by the same single step so the group reads as one block under its origin
 * name; encapsulating them here is what keeps the dialog and the detail page visually identical.
 *
 * @param title Sub-header text (the worker or MCP-server label).
 * @param modifier Modifier applied to the block container.
 * @param content The sub-group's items, rendered under the sub-header.
 */
@Composable
fun AgentToolSubGroupBlock(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = SubGroupIndent),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AgentToolBucketHeader(title)
        content()
    }
}
