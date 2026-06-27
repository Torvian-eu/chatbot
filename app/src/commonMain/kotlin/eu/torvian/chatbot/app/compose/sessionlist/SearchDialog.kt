package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Dialog displaying cross-session search controls and the latest server-backed results.
 *
 * @param isVisible Whether the dialog should be rendered.
 * @param query Current editable query text.
 * @param lastSearchQuery Query that produced the current result state.
 * @param searchResultsState Current loading, success, or error state for the search request.
 * @param onDismiss Request to close the dialog while preserving state.
 * @param onQueryChange Callback for query text changes.
 * @param onSearch Callback that executes a new search.
 * @param onResultClick Callback invoked when the user selects a search result.
 */
@Composable
fun SearchDialog(
    isVisible: Boolean,
    query: String,
    lastSearchQuery: String,
    searchResultsState: DataState<RepositoryError, List<MessageSearchResult>>,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onResultClick: (MessageSearchResult) -> Unit,
) {
    if (!isVisible) {
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 900.dp)
                    .heightIn(min = 320.dp, max = 700.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Search messages",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Search across all of your chat sessions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledIconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close search dialog")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Search text") }
                        )
                        Spacer(Modifier.width(12.dp))
                        FilledIconButton(
                            onClick = onSearch,
                            enabled = query.trim().isNotEmpty() && !searchResultsState.isLoading,
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Run search")
                        }
                    }

                    if (lastSearchQuery.isNotBlank()) {
                        Text(
                            text = "Showing results for “$lastSearchQuery”",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                    ) {
                        when (searchResultsState) {
                            DataState.Idle -> {
                                SearchDialogPlaceholder(
                                    text = "Enter a query and run a search to look across sessions."
                                )
                            }

                            DataState.Loading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }

                            is DataState.Error -> {
                                SearchDialogPlaceholder(
                                    text = searchResultsState.error.message,
                                    isError = true
                                )
                            }

                            is DataState.Success -> {
                                if (searchResultsState.data.isEmpty()) {
                                    SearchDialogPlaceholder(
                                        text = if (lastSearchQuery.isBlank()) {
                                            "No search results available."
                                        } else {
                                            "No matches found for “$lastSearchQuery”."
                                        }
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(
                                            items = searchResultsState.data,
                                            key = MessageSearchResult::messageId
                                        ) { result ->
                                            SearchResultCard(
                                                result = result,
                                                onClick = { onResultClick(result) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lightweight placeholder used for idle, empty, and error states inside [SearchDialog].
 *
 * @param text Message to present to the user.
 * @param isError Whether the text should use the error color.
 */
@Composable
private fun SearchDialogPlaceholder(
    text: String,
    isError: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Renders a single cross-session search result with snippet highlighting and session context.
 *
 * @param result Search result to display.
 * @param onClick Callback invoked when the result is selected.
 */
@Composable
private fun SearchResultCard(
    result: MessageSearchResult,
    onClick: () -> Unit,
) {
    val highlightedSnippet = remember(result) {
        buildHighlightedSnippet(
            snippet = result.snippet,
            matchStartIndex = result.matchStartIndex,
            matchEndExclusive = result.matchEndExclusive,
        )
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = result.sessionName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when (result.messageRole) {
                    ChatMessage.Role.USER -> "User message"
                    ChatMessage.Role.ASSISTANT -> "Assistant message"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = highlightedSnippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Builds an [AnnotatedString] that highlights the matched substring inside a backend-provided
 * snippet so the UI does not need to recompute search positions.
 *
 * @param snippet Snippet text returned by the backend.
 * @param matchStartIndex Inclusive match start offset inside [snippet].
 * @param matchEndExclusive Exclusive match end offset inside [snippet].
 * @return Annotated snippet with a single highlighted range when the offsets are valid.
 */
private fun buildHighlightedSnippet(
    snippet: String,
    matchStartIndex: Int,
    matchEndExclusive: Int,
): AnnotatedString {
    val clampedStartIndex = matchStartIndex.coerceIn(0, snippet.length)
    val clampedEndIndex = matchEndExclusive.coerceIn(clampedStartIndex, snippet.length)
    return buildAnnotatedString {
        append(snippet)
        if (clampedStartIndex < clampedEndIndex) {
            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
                start = clampedStartIndex,
                end = clampedEndIndex,
            )
        }
    }
}