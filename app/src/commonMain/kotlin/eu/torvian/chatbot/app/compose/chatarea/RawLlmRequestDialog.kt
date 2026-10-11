package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_body_label
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_close_button
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_copy_button
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_empty
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_headers_label
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_method_label
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_title
import eu.torvian.chatbot.app.generated.resources.raw_llm_request_url_label
import eu.torvian.chatbot.app.viewmodel.chat.usecase.formatBodySize
import eu.torvian.chatbot.app.viewmodel.chat.usecase.prettyBodyJson
import eu.torvian.chatbot.app.viewmodel.chat.usecase.utf8BodySizeBytes
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only viewer for the latest provider request captured for the active session.
 *
 * The dialog renders whatever the supplied flow currently holds, so a capture that arrives while it stays
 * open appears without reopening it. It shows an explicit empty state instead of blank sections before the
 * first request, and it never offers editing, replay or resending. Every rendered value is selectable and
 * the content area carries a scrollbar, because a request body can be long enough to scroll.
 *
 * @param requestFlow Reactive state of the latest captured provider request, or `null` when there is none.
 * @param onCopy Invoked when the user asks to copy the displayed request.
 * @param onDismiss Invoked when the dialog should close.
 */
@Composable
fun RawLlmRequestDialog(
    requestFlow: StateFlow<RawLlmRequestPayload?>,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val request by requestFlow.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            SelectionContainer {
                Text(stringResource(Res.string.raw_llm_request_title))
            }
        },
        text = {
            // Bound once so the null check and the reads below observe the same value.
            val currentRequest = request
            if (currentRequest == null) {
                SelectionContainer {
                    Text(
                        text = stringResource(Res.string.raw_llm_request_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val scrollState = rememberScrollState()
                ScrollbarWrapper(scrollState = scrollState, modifier = Modifier.fillMaxWidth()) {
                    RawLlmRequestContent(
                        scrollState = scrollState,
                        request = currentRequest,
                        body = currentRequest.prettyBodyJson()
                    )
                }
            }
        },
        confirmButton = {
            // Copying an absent request would be meaningless, so the action only exists with a value.
            if (request != null) {
                TextButton(onClick = onCopy) {
                    Text(stringResource(Res.string.raw_llm_request_copy_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.raw_llm_request_close_button))
            }
        },
        modifier = Modifier.widthIn(min = 400.dp, max = 700.dp)
    )
}

/**
 * Renders the method, URL, headers and body of one captured request.
 *
 * The content is selectable and scrolls through the supplied state so the surrounding scrollbar stays in
 * sync with it; long header values scroll horizontally because wrapping them would hide the value's shape.
 *
 * @param scrollState Vertical scroll state shared with the surrounding scrollbar.
 * @param request Captured request whose fields are rendered.
 * @param body Pretty-printed request body text.
 */
@Composable
private fun RawLlmRequestContent(
    scrollState: ScrollState,
    request: RawLlmRequestPayload,
    body: String
) {
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RawLlmRequestSection(
                label = stringResource(Res.string.raw_llm_request_method_label),
                value = request.method
            )
            RawLlmRequestSection(
                label = stringResource(Res.string.raw_llm_request_url_label),
                value = request.url
            )

            HorizontalDivider()

            Text(
                text = stringResource(Res.string.raw_llm_request_headers_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            request.headers.forEach { (name, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$name:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(Res.string.raw_llm_request_body_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatBodySize(request.utf8BodySizeBytes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Renders one labelled value line of the request viewer.
 *
 * @param label Localized label of the displayed field.
 * @param value Already-formatted value of the displayed field.
 */
@Composable
private fun RawLlmRequestSection(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
