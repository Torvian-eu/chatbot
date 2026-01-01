package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.utils.platform.FilePathUtils
import eu.torvian.chatbot.app.utils.platform.FilePickerResult
import eu.torvian.chatbot.app.utils.platform.ReReadFileResult
import eu.torvian.chatbot.app.utils.platform.reReadFileContent
import eu.torvian.chatbot.app.utils.platform.selectFiles
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.core.FileReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Use case for managing file references in the chat.
 *
 * Handles:
 * - Picking files from the file system and adding them as references
 * - Adding file references programmatically
 * - Removing file references
 * - Toggling content inclusion for file references
 * - Managing the base path for relative paths
 * - Re-reading file content from disk
 * - Managing the file references management dialog
 */
class FileReferenceUseCase(
    private val state: ChatState,
    private val notificationService: NotificationService,
    private val coroutineScope: CoroutineScope
) {

    private val logger = kmpLogger<FileReferenceUseCase>()

    /**
     * Opens a file picker dialog and adds selected files as file references.
     * Uses the current basePathOverride or the common base path of selected files.
     *
     * @return The FilePickerResult indicating success, cancellation, or error
     */
    suspend fun pickAndAddFiles(): FilePickerResult {
        val result = selectFiles(initialDirectory = state.basePathOverride.value)

        when (result) {
            is FilePickerResult.Success -> {
                logger.info("File picker succeeded with ${result.files.size} files")

                val currentOverride = state.basePathOverride.value
                val currentReferences = state.pendingFileReferences.value

                // Determine the base path to use
                // If no override is set, use the new files' common base path
                // If override is set, verify it's still valid for all files (old + new)
                val newBasePath = if (currentOverride != null) {
                    // Check if the override is still a valid common ancestor
                    val allFilesPaths = currentReferences.map { it.fullPath } + result.files.map { it.absolutePath }
                    if (FilePathUtils.isValidCommonBasePath(currentOverride, allFilesPaths)) {
                        currentOverride
                    } else {
                        // Override is no longer valid, recalculate from all files
                        logger.info("BasePathOverride is no longer valid for all files, recalculating")
                        FilePathUtils.calculateCommonBasePath(allFilesPaths)
                    }
                } else {
                    FilePathUtils.calculateCommonBasePath(result.files.map { it.absolutePath })
                }

                // If base path changed, update all existing file references
                if (newBasePath != currentOverride) {
                    logger.info("Base path changed from $currentOverride to $newBasePath, updating all file references")
                    state.setBasePathOverride(newBasePath)

                    // Recalculate relative paths for all existing references
                    state.updateFileReferences { currentList ->
                        currentList.map { ref ->
                            val newRelativePath = FilePathUtils.calculateRelativePath(newBasePath, ref.fullPath)
                            ref.copy(basePath = newBasePath, relativePath = newRelativePath)
                        }
                    }
                }

                val fileReferences = result.files.map { fileInfo ->
                    // Calculate relative path from base path
                    val relativePath = FilePathUtils.calculateRelativePath(newBasePath, fileInfo.absolutePath)
                    FileReference(
                        basePath = newBasePath,
                        relativePath = relativePath,
                        fileSize = fileInfo.fileSize,
                        lastModified = fileInfo.lastModified,
                        mimeType = fileInfo.mimeType,
                        content = fileInfo.content,
                        inlinePosition = null
                    )
                }

                state.updateFileReferences { currentList -> currentList + fileReferences }
                logger.info("Added ${fileReferences.size} file references")
            }
            is FilePickerResult.Cancelled -> {
                logger.info("File picker was cancelled by user")
            }
            is FilePickerResult.Error -> {
                logger.error("File picker error: ${result.message}")
                notificationService.genericError(
                    shortMessage = "Failed to select files",
                    detailedMessage = result.message
                )
            }
        }

        return result
    }

    /**
     * Adds file references to the current message being composed.
     *
     * @param fileReferences List of file references to add
     */
    fun addFileReferences(fileReferences: List<FileReference>) {
        logger.info("Adding ${fileReferences.size} file references")
        state.updateFileReferences { currentList -> currentList + fileReferences }
    }

    /**
     * Removes a file reference from the current message being composed.
     *
     * @param fileReference The file reference to remove
     */
    fun removeFileReference(fileReference: FileReference) {
        logger.info("Removing file reference: ${fileReference.relativePath}")
        state.updateFileReferences { currentList -> currentList - fileReference }
    }

    /**
     * Clears all pending file references.
     */
    fun clearAllFileReferences() {
        logger.info("Clearing all file references")
        state.updateFileReferences { emptyList() }
    }

    /**
     * Updates the base path override for file references.
     * Validates that the new path is a valid common ancestor for all pending file references.
     * Recalculates basePath and relative paths for all existing file references.
     * Normalizes the path to use forward slashes for consistency.
     *
     * @param path The new base path, or null to clear the override and set individual base paths per file
     */
    suspend fun updateBasePath(path: String?) {
        logger.info("Updating base path to: $path")

        // If clearing the override, set each file reference to use its individual directory as base path
        // and just the filename as relative path
        if (path == null) {
            state.setBasePathOverride(null)

            val currentReferences = state.pendingFileReferences.value
            if (currentReferences.isNotEmpty()) {
                logger.info("Clearing override, setting individual base paths for each file")

                // Update all file references to use their individual directory as base path
                state.updateFileReferences { refs ->
                    refs.map { ref ->
                        val (basePath, fileName) = FilePathUtils.splitPathAndFilename(ref.fullPath)
                        ref.copy(basePath = basePath, relativePath = fileName)
                    }
                }
            }
            return
        }

        // Normalize the path to use forward slashes
        val normalizedPath = path.replace('\\', '/')

        // Validate that the new path is a valid common ancestor for all file references
        val allFilesPaths = state.pendingFileReferences.value.map { it.fullPath }
        if (allFilesPaths.isNotEmpty() && !FilePathUtils.isValidCommonBasePath(normalizedPath, allFilesPaths)) {
            logger.warn("Invalid base path: $normalizedPath is not a valid common ancestor for all file references")
            notificationService.genericError(
                shortMessage = "Invalid base path",
                detailedMessage = "The specified path must be a common ancestor for all file references"
            )
            return
        }

        // Update the base path override with normalized path
        state.setBasePathOverride(normalizedPath)

        // Recalculate basePath and relative paths for all existing file references
        state.updateFileReferences { currentReferences ->
            currentReferences.map { ref ->
                val newRelativePath = FilePathUtils.calculateRelativePath(normalizedPath, ref.fullPath)
                ref.copy(basePath = normalizedPath, relativePath = newRelativePath)
            }
        }

        logger.info("Updated base path and recalculated relative paths for ${state.pendingFileReferences.value.size} file references")
    }

    /**
     * Resets the base path override to the common base path of all current pending file references.
     * If no file references exist, clears the base path override.
     */
    suspend fun resetBasePathToCommonPath() {
        val currentReferences = state.pendingFileReferences.value

        if (currentReferences.isEmpty()) {
            logger.info("No file references, clearing base path override")
            state.setBasePathOverride(null)
            return
        }

        val allFilesPaths = currentReferences.map { it.fullPath }
        val commonBasePath = FilePathUtils.calculateCommonBasePath(allFilesPaths)

        logger.info("Resetting base path to common path: $commonBasePath")
        updateBasePath(commonBasePath)
    }

    /**
     * Toggles content inclusion for a file reference.
     * When enabling content, re-reads the file from disk.
     * When disabling content, clears the content field.
     *
     * @param fileReference The file reference to update
     * @param includeContent Whether to include content
     */
    suspend fun toggleFileContent(fileReference: FileReference, includeContent: Boolean) {
        logger.info("Toggling content for ${fileReference.relativePath} to $includeContent")

        // If disabling content, simply update the reference
        if (!includeContent) {
            logger.info("Clearing content for ${fileReference.relativePath}")
            state.updateFileReferences { currentReferences ->
                currentReferences.map { ref ->
                    if (ref == fileReference) ref.copy(content = null) else ref
                }
            }
            return
        }

        // If enabling content, first read the file, then update
        when (val result = reReadFileContent(fileReference.fullPath)) {
            is ReReadFileResult.Success -> {
                logger.info("Successfully re-read file content for ${fileReference.relativePath}")
                state.updateFileReferences { currentReferences ->
                    currentReferences.map { ref ->
                        if (ref == fileReference) {
                            ref.copy(
                                content = result.content,
                                fileSize = result.fileSize,
                                lastModified = result.lastModified
                            )
                        } else {
                            ref
                        }
                    }
                }
            }
            is ReReadFileResult.Error -> {
                logger.error("Failed to re-read file content: ${result.message}")
                notificationService.genericWarning(
                    shortMessage = "Could not read file content for ${fileReference.relativePath}",
                    detailedMessage = result.message
                )
            }
        }
    }

    /**
     * Shows the file references management dialog.
     * Manages all dialog state and actions internally.
     */
    fun showFileReferencesManagementDialog() {
        logger.info("Opening file references management dialog")

        state.setDialogState(
            ChatAreaDialogState.FileReferencesManagement(
                fileReferencesFlow = state.pendingFileReferences,
                basePathFlow = state.basePathOverride,
                onBasePathChange = { newPath ->
                    coroutineScope.launch {
                        updateBasePath(newPath)
                    }
                },
                onResetBasePath = {
                    coroutineScope.launch {
                        resetBasePathToCommonPath()
                    }
                },
                onAddFiles = {
                    coroutineScope.launch {
                        pickAndAddFiles()
                    }
                },
                onRemoveFile = { fileRef ->
                    removeFileReference(fileRef)
                },
                onToggleContent = { fileRef, includeContent ->
                    coroutineScope.launch {
                        toggleFileContent(fileRef, includeContent)
                    }
                },
                onDismiss = {
                    dismissDialog()
                }
            )
        )
    }

    /**
     * Shows the file reference details dialog for a specific file.
     *
     * @param fileReference The file reference to show details for
     */
    fun showFileReferenceDetailsDialog(fileReference: FileReference) {
        logger.info("Opening file reference details dialog for: ${fileReference.relativePath}")
        state.setDialogState(
            ChatAreaDialogState.FileReferenceDetails(
                fileReference = fileReference,
                onDismiss = {
                    dismissDialog()
                }
            )
        )
    }

    /**
     * Dismisses any currently open file reference dialog.
     */
    private fun dismissDialog() {
        logger.info("Dismissing file reference dialog")
        state.setDialogState(ChatAreaDialogState.None)
    }
}
