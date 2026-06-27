package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.chat.search.SearchDirection
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox


/**
 * Compact top-bar search UI for in-session message search.
 *
 * @param query current search query.
 * @param currentIndex currently selected result index, or `-1` when none is selected.
 * @param resultCount total number of matching occurrences.
 * @param onQueryChange updates the current search query.
 * @param onNavigate cycles through the available results.
 * @param onJumpToResult jumps directly to a zero-based result index.
 * @param onClose closes search mode and clears the current query.
 * @param modifier modifier applied to the outer row.
 */
@Composable
fun SearchBar(
    query: String,
    currentIndex: Int,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onNavigate: (SearchDirection) -> Unit,
    onJumpToResult: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var isJumpDialogVisible by remember { mutableStateOf(false) }
    var requestedResultText by remember(resultCount, currentIndex) {
        mutableStateOf(if (currentIndex >= 0) (currentIndex + 1).toString() else "")
    }
    val selectedResultNumber = if (currentIndex >= 0) currentIndex + 1 else 0
    val requestedResultNumber = requestedResultText.toIntOrNull()
    val canJumpToRequestedResult = requestedResultNumber != null && requestedResultNumber in 1..resultCount

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
            // Ignore transient focus failures during top-bar recomposition.
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            label = { Text("Search messages", maxLines = 1) },
            placeholder = { Text("Find in current thread", maxLines = 1) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.width(8.dp))

        PlainTooltipBox(text = "Previous result") {
            IconButton(onClick = { onNavigate(SearchDirection.BACKWARD) }, enabled = resultCount > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous result")
            }
        }

        PlainTooltipBox(text = "Next result") {
            IconButton(onClick = { onNavigate(SearchDirection.FORWARD) }, enabled = resultCount > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next result")
            }
        }

        PlainTooltipBox(text = "Jump to result") {
            TextButton(onClick = { isJumpDialogVisible = true }, enabled = resultCount > 0) {
                Text("$selectedResultNumber/$resultCount")
            }
        }

        PlainTooltipBox(text = "Close search") {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        }
    }

    if (isJumpDialogVisible) {
        AlertDialog(
            onDismissRequest = { isJumpDialogVisible = false },
            title = { Text("Jump to result") },
            text = {
                OutlinedTextField(
                    value = requestedResultText,
                    onValueChange = { requestedResultText = it.filter(Char::isDigit) },
                    label = { Text("Result number") },
                    placeholder = { Text("1-$resultCount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onJumpToResult(requireNotNull(requestedResultNumber) - 1)
                        isJumpDialogVisible = false
                    },
                    enabled = canJumpToRequestedResult,
                ) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { isJumpDialogVisible = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
