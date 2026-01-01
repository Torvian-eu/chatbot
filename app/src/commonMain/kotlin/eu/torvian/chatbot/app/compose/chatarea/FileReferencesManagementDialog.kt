package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.core.FileReference
import kotlinx.coroutines.flow.StateFlow

/**
 * Dialog for managing pending file references before sending a message.
 * Allows users to:
 * - View all attached files
 * - Add more files
 * - Remove individual files
 * - Toggle content inclusion per file
 * - Edit the base path for relative paths
 * - Reset base path to common path of current files
 *
 * @param fileReferencesFlow Reactive flow of pending file references
 * @param basePathFlow Reactive flow of current base path for relative paths
 * @param onBasePathChange Called when user changes the base path
 * @param onResetBasePath Called when user wants to reset base path to common path
 * @param onAddFiles Called when user wants to add more files
 * @param onRemoveFile Called when user removes a file
 * @param onToggleContent Called when user toggles content inclusion for a file
 * @param onDismiss Called when dialog should be dismissed
 */
@Composable
fun FileReferencesManagementDialog(
    fileReferencesFlow: StateFlow<List<FileReference>>,
    basePathFlow: StateFlow<String?>,
    onBasePathChange: (String?) -> Unit,
    onResetBasePath: () -> Unit,
    onAddFiles: () -> Unit,
    onRemoveFile: (FileReference) -> Unit,
    onToggleContent: (FileReference, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val fileReferences by fileReferencesFlow.collectAsState()
    val basePath by basePathFlow.collectAsState()

    var editingBasePath by remember { mutableStateOf(false) }
    var basePathInput by remember(basePath) { mutableStateOf(basePath ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Attached Files (${fileReferences.size})")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Base Path Section
                Text(
                    text = "Base Path",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (editingBasePath) {
                    OutlinedTextField(
                        value = basePathInput,
                        onValueChange = { basePathInput = it },
                        label = { Text("Base Path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                TextButton(onClick = {
                                    onBasePathChange(basePathInput.ifBlank { null })
                                    editingBasePath = false
                                }) {
                                    Text("Save")
                                }
                                IconButton(onClick = {
                                    basePathInput = basePath ?: ""
                                    editingBasePath = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                }
                            }
                        }
                    )
                    Text(
                        text = "The base path is used to calculate relative paths sent to the LLM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = basePath ?: "(using file locations)",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (basePath != null) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(
                            onClick = { onBasePathChange(null) },
                            modifier = Modifier.size(32.dp),
                            enabled = basePath != null
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear base path override",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onResetBasePath,
                            modifier = Modifier.size(32.dp),
                            enabled = fileReferences.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset to common path",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { editingBasePath = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit base path",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (fileReferences.isNotEmpty()) {
                        Text(
                            text = "Clear to use individual file locations, or refresh to reset to common path",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                // Files Section Header with Add button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Files",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    TextButton(
                        onClick = onAddFiles
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

                if (fileReferences.isEmpty()) {
                    Text(
                        text = "No files attached",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    fileReferences.forEach { fileRef ->
                        FileReferenceManagementItem(
                            fileReference = fileRef,
                            onRemove = { onRemoveFile(fileRef) },
                            onToggleContent = { includeContent ->
                                onToggleContent(fileRef, includeContent)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        modifier = Modifier.widthIn(min = 450.dp, max = 600.dp)
    )
}

@Composable
private fun FileReferenceManagementItem(
    fileReference: FileReference,
    onRemove: () -> Unit,
    onToggleContent: (Boolean) -> Unit
) {
    val hasContent = fileReference.content != null

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = fileReference.fileName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = fileReference.relativePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove file",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Include content",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (hasContent) "Content will be sent to LLM" else "Only file reference",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = hasContent,
                    onCheckedChange = onToggleContent,
                    enabled = true // Content toggle is always enabled
                )
            }

            // Show file info
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = formatFileSize(fileReference.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = fileReference.mimeType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> {
            val mb = bytes / (1024.0 * 1024.0)
            "${(mb * 100).toLong() / 100.0} MB"
        }
    }
}

