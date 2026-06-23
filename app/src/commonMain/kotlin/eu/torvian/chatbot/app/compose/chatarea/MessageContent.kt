package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.markdown.MarkdownHighlightedText
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference

/**
 * Composable for displaying the content of a message, including editing capabilities.
 *
 * @param message The message data.
 * @param isBeingEdited Whether this message is currently being edited.
 * @param editingContent The current editing content if this message is being edited.
 * @param editingFileReferences The file references being edited.
 * @param editingBasePathOverride The base path override for editing file references.
 * @param messageActions Message item actions, including edit callbacks.
 * @param contentColor The color to be used for the content, respecting the current theme.
 * @param isCollapsed Whether this message content is collapsed (showing truncated preview).
 * @param searchContext optional in-session search rendering context. When `null`, the content is
 * rendered without highlights and without search-specific geometry tracking.
 */
@Composable
fun MessageContent(
    message: ChatMessage,
    isBeingEdited: Boolean,
    editingContent: String?,
    editingFileReferences: List<FileReference>,
    editingBasePathOverride: String?,
    messageActions: MessageActions,
    contentColor: Color,
    isCollapsed: Boolean = false,
    searchContext: MessageSearchContext? = null,
) {
    // Preview length for truncated content
    val previewLength = 200

    if (isBeingEdited) {
        LaunchedEffect(searchContext) {
            // Editing replaces the rendered markdown block, so any previous search geometry is stale.
            searchContext?.onSelectedOccurrenceCenterInContentChanged(null)
        }

        val state = rememberTextFieldState(editingContent ?: message.content)

        // Sync external changes (like undo/redo from ViewModel) into the state
        LaunchedEffect(editingContent) {
            if (editingContent != null && editingContent != state.text.toString()) {
                state.setTextAndPlaceCursorAtEnd(editingContent)
            }
        }

        // Sync internal changes back to the ViewModel
        LaunchedEffect(state) {
            snapshotFlow { state.text }
                .collect { newText ->
                    val textString = newText.toString()
                    if (textString != editingContent) {
                        messageActions.onUpdateEditingContent(textString)
                    }
                }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp), // BasicTextField needs manual padding
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                cursorBrush = SolidColor(contentColor),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1),
                decorator = { innerTextField ->
                    // This allows us to keep the placeholder logic
                    Box {
                        if (state.text.isEmpty()) {
                            Text(
                                "Edit your message...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // File references management for editing
            if (editingFileReferences.isNotEmpty()) {
                EditingFileReferencesSection(
                    fileReferences = editingFileReferences,
                    basePathOverride = editingBasePathOverride,
                    onAddFiles = { messageActions.onAddEditingFileReferences() },
                    onRemoveFile = { messageActions.onRemoveEditingFileReference(it) },
                    onToggleContent = { ref, includeContent ->
                        messageActions.onToggleEditingFileContent(ref, includeContent)
                    },
                    onSetBasePath = { messageActions.onSetEditingBasePathOverride(it) },
                    onResetBasePath = { messageActions.onResetEditingBasePath() },
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                // Show "Add Files" button when no files are attached
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(
                        onClick = { messageActions.onAddEditingFileReferences() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Add files",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Add Files")
                    }
                }
            }

            // Actions for the edited message (Save, Cancel)
            MessageEditActions(
                onSave = {
                    messageActions.onSaveEditing()
                },
                onSaveAsCopy = {
                    messageActions.onSaveEditingAsCopy()
                },
                onCancel = {
                    messageActions.onCancelEditing()
                },
                modifier = Modifier.padding(top = 8.dp) // Padding between text field and actions
            )
        }
    } else {
        val previewContent = remember(message.content) { message.content.take(previewLength) + "..." }
        val previewSourceLength = remember(message.content) { message.content.length.coerceAtMost(previewLength) }
        val selectedSearchMatch = searchContext?.selectedMatch
        val effectiveIsCollapsed = isCollapsed && selectedSearchMatch == null
        val previewHighlightSpans = if (searchContext != null) {
            rememberSearchHighlightSpans(searchContext.matches, selectedSearchMatch, previewSourceLength)
        } else {
            emptyList()
        }
        val fullContentHighlightSpans = if (searchContext != null) {
            rememberSearchHighlightSpans(searchContext.matches, selectedSearchMatch, message.content.length)
        } else {
            emptyList()
        }
        var expandedContentTextLayoutResult by remember(message.id, message.content) {
            mutableStateOf<TextLayoutResult?>(null)
        }

        LaunchedEffect(
            selectedSearchMatch,
            effectiveIsCollapsed,
            expandedContentTextLayoutResult,
            searchContext?.onSelectedOccurrenceCenterInContentChanged,
        ) {
            val reportSelectedOccurrenceCenter = searchContext?.onSelectedOccurrenceCenterInContentChanged
                ?: return@LaunchedEffect

            if (selectedSearchMatch == null || effectiveIsCollapsed) {
                reportSelectedOccurrenceCenter(null)
            } else {
                reportSelectedOccurrenceCenter(
                    expandedContentTextLayoutResult?.let { layoutResult ->
                        computeSelectedSearchMatchCenterY(layoutResult, selectedSearchMatch)
                    }
                )
            }
        }

        Column {
            AnimatedVisibility(
                visible = effectiveIsCollapsed,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                SelectionContainer {
                    MarkdownHighlightedText(
                        markdown = previewContent,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        extraSpanStyles = previewHighlightSpans,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { messageActions.onToggleMessageCollapsed(message.id) }
                    )
                }
            }

            AnimatedVisibility(
                visible = !effectiveIsCollapsed,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                SelectionContainer {
                    MarkdownHighlightedText(
                        markdown = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        extraSpanStyles = fullContentHighlightSpans,
                        onTextLayout = { textLayoutResult ->
                            if (searchContext != null) {
                                expandedContentTextLayoutResult = textLayoutResult
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Builds remembered search highlight spans for the provided message content.
 *
 * @param searchMatches all matches to highlight in the rendered message.
 * @param selectedSearchMatch currently selected occurrence in the message, if any.
 * @param maxEndExclusive exclusive upper bound of the rendered text segment to style.
 * @return Compose span ranges that overlay search highlighting on top of markdown styling.
 */
@Composable
private fun rememberSearchHighlightSpans(
    searchMatches: List<MessageSearchMatch>,
    selectedSearchMatch: MessageSearchMatch?,
    maxEndExclusive: Int,
): List<AnnotatedString.Range<SpanStyle>> {
    val regularHighlightColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
    val selectedHighlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)

    return remember(searchMatches, selectedSearchMatch, maxEndExclusive, regularHighlightColor, selectedHighlightColor) {
        buildSearchHighlightSpans(
            searchMatches = searchMatches,
            selectedSearchMatch = selectedSearchMatch,
            regularHighlightColor = regularHighlightColor,
            selectedHighlightColor = selectedHighlightColor,
            maxEndExclusive = maxEndExclusive,
        )
    }
}

/**
 * Converts occurrence-level search matches into overlay span styles for markdown text rendering.
 *
 * Non-selected matches receive the regular search background, while the selected occurrence is
 * appended last with a stronger background so it visually wins if styles overlap.
 *
 * @param searchMatches all matches found in the message.
 * @param selectedSearchMatch currently selected occurrence in the same message, if any.
 * @param regularHighlightColor background used for non-selected matches.
 * @param selectedHighlightColor background used for the selected match.
 * @param maxEndExclusive exclusive upper bound of the rendered text segment to style.
 * @return overlay spans suitable for [MarkdownHighlightedText].
 */
internal fun buildSearchHighlightSpans(
    searchMatches: List<MessageSearchMatch>,
    selectedSearchMatch: MessageSearchMatch?,
    regularHighlightColor: Color,
    selectedHighlightColor: Color,
    maxEndExclusive: Int,
): List<AnnotatedString.Range<SpanStyle>> {
    val visibleMatches = searchMatches.filter { match ->
        match.startIndex >= 0 && match.endExclusive <= maxEndExclusive && match.startIndex < match.endExclusive
    }
    if (visibleMatches.isEmpty()) {
        return emptyList()
    }

    return buildList {
        visibleMatches.filter { it != selectedSearchMatch }.forEach { match ->
            add(
                AnnotatedString.Range(
                    item = SpanStyle(background = regularHighlightColor),
                    start = match.startIndex,
                    end = match.endExclusive,
                )
            )
        }
        visibleMatches.firstOrNull { it == selectedSearchMatch }?.let { match ->
            add(
                AnnotatedString.Range(
                    item = SpanStyle(background = selectedHighlightColor),
                    start = match.startIndex,
                    end = match.endExclusive,
                )
            )
        }
    }
}

/**
 * Composable for displaying the actions available for an edited message.
 *
 * @param onSave Callback for the save action.
 * @param onSaveAsCopy Callback for the save as copy action.
 * @param onCancel Callback for the cancel action.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
private fun MessageEditActions(
    onSave: () -> Unit,
    onSaveAsCopy: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier.Companion
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cancel Button
        OutlinedButton(
            onClick = onCancel
        ) {
            Text("Cancel")
        }

        // Save as Copy Button
        FilledTonalButton(
            onClick = onSaveAsCopy
        ) {
            Text("Save as Copy")
        }

        // Save Button
        Button(
            onClick = onSave
        ) {
            Text("Save")
        }
    }
}

/**
 * Section for managing file references while editing a message.
 */
@Composable
private fun EditingFileReferencesSection(
    fileReferences: List<FileReference>,
    basePathOverride: String?,
    onAddFiles: () -> Unit,
    onRemoveFile: (FileReference) -> Unit,
    onToggleContent: (FileReference, Boolean) -> Unit,
    onSetBasePath: (String?) -> Unit,
    onResetBasePath: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingBasePath by remember { mutableStateOf(false) }
    var basePathInput by remember(basePathOverride) { mutableStateOf(basePathOverride ?: "") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Files Section Header with Add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Files (${fileReferences.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            TextButton(onClick = onAddFiles) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Add files",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Add", style = MaterialTheme.typography.labelSmall)
            }
        }

        // File list
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            fileReferences.forEach { fileRef ->
                EditingFileReferenceItem(
                    fileReference = fileRef,
                    onRemove = { onRemoveFile(fileRef) },
                    onToggleContent = { includeContent ->
                        onToggleContent(fileRef, includeContent)
                    }
                )
            }
        }

        // Base Path Section
        Text(
            text = "Base Path",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        HorizontalDivider()

        if (editingBasePath) {
            OutlinedTextField(
                value = basePathInput,
                onValueChange = { basePathInput = it },
                label = { Text("Base Path") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    Row {
                        TextButton(onClick = {
                            onSetBasePath(basePathInput.ifBlank { null })
                            editingBasePath = false
                        }) {
                            Text("Save", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            basePathInput = basePathOverride ?: ""
                            editingBasePath = false
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = basePathOverride ?: "(using file locations)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (basePathOverride != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(
                    onClick = { onSetBasePath(null) },
                    modifier = Modifier.size(24.dp),
                    enabled = basePathOverride != null
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear base path override",
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = onResetBasePath,
                    modifier = Modifier.size(24.dp),
                    enabled = fileReferences.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset to common path",
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = { editingBasePath = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit base path",
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual file reference item in the editing UI.
 */
@Composable
private fun EditingFileReferenceItem(
    fileReference: FileReference,
    onRemove: () -> Unit,
    onToggleContent: (Boolean) -> Unit
) {
    val hasContent = fileReference.content != null

    ElevatedCard(
        modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // File info header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileReference.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = fileReference.relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove file",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Include content toggle
            Surface(
                onClick = { onToggleContent(!hasContent) },
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Include content",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasContent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Checkbox(
                        checked = hasContent,
                        onCheckedChange = onToggleContent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}