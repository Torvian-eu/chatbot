package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.utils.platform.FilePathUtils
import eu.torvian.chatbot.app.utils.platform.FilePickerResult
import eu.torvian.chatbot.app.utils.platform.ReReadFileResult
import eu.torvian.chatbot.app.utils.platform.reReadFileContent
import eu.torvian.chatbot.app.utils.platform.selectFiles
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition

/**
 * Use case for editing chat messages.
 * Handles the editing workflow including validation, repository calls, and state updates.
 */
class EditMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<EditMessageUseCase>()

    /**
     * Starts editing a message by setting the editing state.
     *
     * @param message The message to edit
     */
    fun start(message: ChatMessage) {
        logger.info("Starting to edit message ${message.id}")
        state.setEditingMessage(message)
        state.setEditingContent(message.content)
        state.setEditingFileReferences(message.fileReferences)
        state.setEditingBasePathOverride(null)
    }

    /**
     * Updates the content of the message currently being edited.
     * Called by the UI as the user types in the editing input field.
     *
     * @param newText The new text content for the editing field
     */
    fun updateContent(newText: String) {
        state.setEditingContent(newText)
    }

    /**
     * Saves the edited message content and file references to the server.
     */
    suspend fun save() {
        val messageToEdit = state.editingMessage.value ?: return
        val newContent = state.editingContent.value
        val newFileReferences = state.editingFileReferences.value

        logger.info("Saving edited message ${messageToEdit.id}")

        sessionRepository.updateMessageContent(
            messageToEdit.id,
            messageToEdit.sessionId,
            newContent,
            newFileReferences
        )
            .fold(
                ifLeft = { repositoryError ->
                    logger.error("Edit message repository error: ${repositoryError.message}")
                    notificationService.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to save message edit"
                    )
                },
                ifRight = {
                    logger.info("Successfully saved edited message ${messageToEdit.id}")
                    // Clear editing state on success
                    cancel()
                }
            )
    }

    /**
     * Saves the edited message content as a new copy (sibling).
     * Creates a new branch in the conversation.
     */
    suspend fun saveAsCopy() {
        val messageToEdit = state.editingMessage.value ?: return
        val newContent = state.editingContent.value
        val newFileReferences = state.editingFileReferences.value

        val parentId = messageToEdit.parentMessageId
        val session = state.currentSession.value ?: return
        val modelId = session.currentModelId
        val settingsId = session.currentSettingsId

        logger.info("Saving edited message ${messageToEdit.id} as copy (sibling)")

        sessionRepository.insertMessage(
            sessionId = session.id,
            targetMessageId = parentId,
            position = MessageInsertPosition.APPEND,
            role = messageToEdit.role,
            content = newContent,
            modelId = if (messageToEdit.role == ChatMessage.Role.ASSISTANT) modelId else null,
            settingsId = if (messageToEdit.role == ChatMessage.Role.ASSISTANT) settingsId else null,
            fileReferences = newFileReferences
        ).fold(
            ifLeft = { error ->
                notificationService.repositoryError(error, "Failed to save copy")
            },
            ifRight = {
                logger.info("Successfully saved copy")
                cancel()
            }
        )
    }

    /**
     * Cancels the message editing state.
     */
    fun cancel() {
        logger.info("Cancelling message editing")
        state.setEditingMessage(null)
        state.setEditingContent("")
        state.setEditingFileReferences(emptyList())
        state.setEditingBasePathOverride(null)
    }

    // --- File Reference Management for Editing ---

    /**
     * Opens a file picker and adds selected files to the editing file references.
     */
    suspend fun pickAndAddFiles(): FilePickerResult {
        val result = selectFiles(initialDirectory = state.editingBasePathOverride.value)

        when (result) {
            is FilePickerResult.Success -> {
                logger.info("File picker succeeded with ${result.files.size} files")

                val currentOverride = state.editingBasePathOverride.value
                val currentReferences = state.editingFileReferences.value

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
                    state.setEditingBasePathOverride(newBasePath)

                    // Recalculate relative paths for all existing references
                    state.updateEditingFileReferences { currentList ->
                        currentList.map { ref ->
                            val newRelativePath = FilePathUtils.calculateRelativePath(newBasePath, ref.fullPath)
                            ref.copy(basePath = newBasePath, relativePath = newRelativePath)
                        }
                    }
                }

                val fileReferences = result.files.map { fileInfo ->
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

                state.updateEditingFileReferences { currentList -> currentList + fileReferences }
                logger.info("Added ${fileReferences.size} file references to editing message")
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
     * Removes a file reference from the editing file references.
     */
    fun removeFileReference(fileReference: FileReference) {
        logger.info("Removing file reference from editing: ${fileReference.relativePath}")
        state.updateEditingFileReferences { currentList -> currentList - fileReference }
    }

    /**
     * Toggles content inclusion for a file reference in editing.
     * When enabling content, re-reads the file from disk.
     * When disabling content, clears the content field.
     */
    suspend fun toggleFileContent(fileReference: FileReference, includeContent: Boolean) {
        logger.info("Toggling content for ${fileReference.relativePath}: $includeContent")

        // If disabling content, simply update the reference
        if (!includeContent) {
            logger.info("Clearing content for ${fileReference.relativePath}")
            state.updateEditingFileReferences { currentList ->
                currentList.map { ref ->
                    if (ref == fileReference) ref.copy(content = null) else ref
                }
            }
            return
        }

        // If enabling content, first read the file, then update
        when (val result = reReadFileContent(fileReference.fullPath)) {
            is ReReadFileResult.Success -> {
                logger.info("Successfully re-read file content for ${fileReference.relativePath}")
                state.updateEditingFileReferences { currentList ->
                    currentList.map { ref ->
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
     * Updates the base path override for editing file references.
     * Validates that the new path is a valid common ancestor for all editing file references.
     * Recalculates basePath and relative paths for all existing file references.
     * Normalizes the path to use forward slashes for consistency.
     *
     * @param path The new base path, or null to clear the override and recalculate from file references
     */
    suspend fun updateBasePath(path: String?) {
        logger.info("Updating editing base path to: $path")

        // If clearing the override, set each file reference to use its individual directory as base path
        // and just the filename as relative path
        if (path == null) {
            state.setEditingBasePathOverride(null)

            val currentReferences = state.editingFileReferences.value
            if (currentReferences.isNotEmpty()) {
                logger.info("Clearing override, setting individual base paths for each file")

                // Update all file references to use their individual directory as base path
                state.updateEditingFileReferences { refs ->
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
        val allFilesPaths = state.editingFileReferences.value.map { it.fullPath }
        if (allFilesPaths.isNotEmpty() && !FilePathUtils.isValidCommonBasePath(normalizedPath, allFilesPaths)) {
            logger.warn("Invalid base path: $normalizedPath is not a valid common ancestor for all file references")
            notificationService.genericError(
                shortMessage = "Invalid base path",
                detailedMessage = "The specified path must be a common ancestor for all file references"
            )
            return
        }

        // Update the base path override with normalized path
        state.setEditingBasePathOverride(normalizedPath)

        // Recalculate basePath and relative paths for all existing file references
        state.updateEditingFileReferences { currentReferences ->
            currentReferences.map { ref ->
                val newRelativePath = FilePathUtils.calculateRelativePath(normalizedPath, ref.fullPath)
                ref.copy(basePath = normalizedPath, relativePath = newRelativePath)
            }
        }

        logger.info("Updated editing base path and recalculated relative paths for ${state.editingFileReferences.value.size} file references")
    }

    /**
     * Resets the base path override to the common base path of all current editing file references.
     * If no file references exist, clears the base path override.
     */
    suspend fun resetBasePathToCommonPath() {
        val currentReferences = state.editingFileReferences.value

        if (currentReferences.isEmpty()) {
            logger.info("No file references, clearing editing base path override")
            state.setEditingBasePathOverride(null)
            return
        }

        val allFilesPaths = currentReferences.map { it.fullPath }
        val commonBasePath = FilePathUtils.calculateCommonBasePath(allFilesPaths)

        logger.info("Resetting editing base path to common path: $commonBasePath")
        updateBasePath(commonBasePath)
    }
}
