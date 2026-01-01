package eu.torvian.chatbot.app.utils.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop implementation of file picker using JFileChooser.
 * Uses Dispatchers.Swing to ensure JFileChooser operations run on the Event Dispatch Thread (EDT).
 */
actual suspend fun selectFiles(
    includeContent: Boolean,
    maxContentSize: Long,
    initialDirectory: String?
): FilePickerResult {
    val selectedFiles = showFileChooserDialog(initialDirectory) ?: return FilePickerResult.Cancelled

    if (selectedFiles.isEmpty()) {
        return FilePickerResult.Cancelled
    }

    return processSelectedFiles(selectedFiles, includeContent, maxContentSize)
}

/**
 * Shows the file chooser dialog on the Swing EDT.
 *
 * @param initialDirectory The initial directory to open in, or null for default.
 * @return List of selected files, or null if cancelled/error.
 */
private suspend fun showFileChooserDialog(initialDirectory: String?): List<File>? = withContext(Dispatchers.Swing) {
    val fileChooser = createFileChooser()

    // Set initial directory if provided and valid
    if (initialDirectory != null) {
        val initialDir = File(initialDirectory)
        if (initialDir.exists() && initialDir.isDirectory) {
            fileChooser.currentDirectory = initialDir
        }
    }

    when (fileChooser.showOpenDialog(null)) {
        JFileChooser.APPROVE_OPTION -> fileChooser.selectedFiles.toList()
        else -> null
    }
}

/**
 * Creates and configures a JFileChooser for multi-file selection.
 */
private fun createFileChooser(): JFileChooser = JFileChooser().apply {
    dialogTitle = "Select Files"
    isMultiSelectionEnabled = true
    fileSelectionMode = JFileChooser.FILES_ONLY

    addChoosableFileFilter(
        FileNameExtensionFilter("Text Files", "txt", "md", "json", "xml", "yaml", "yml", "csv")
    )
    addChoosableFileFilter(
        FileNameExtensionFilter("Source Code", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "go", "rs")
    )
    addChoosableFileFilter(
        FileNameExtensionFilter("All Files", "*")
    )
}

/**
 * Processes selected files and extracts metadata.
 */
private suspend fun processSelectedFiles(
    files: List<File>,
    includeContent: Boolean,
    maxContentSize: Long
): FilePickerResult = withContext(Dispatchers.IO) {
    var remainingQuota = maxContentSize

    val fileInfoList = files.mapNotNull { file ->
        val metadata = TextFileReader.readMetadata(file) ?: return@mapNotNull null

        val content = if (includeContent) {
            TextFileReader.readTextContentIfEligible(metadata, remainingQuota)?.also {
                remainingQuota -= it.length
            }
        } else {
            null
        }

        SelectedFileInfo(
            absolutePath = metadata.file.absolutePath,
            fileName = metadata.file.name,
            fileSize = metadata.size,
            lastModified = metadata.lastModified,
            mimeType = metadata.mimeType,
            content = content
        )
    }

    if (fileInfoList.isEmpty()) {
        return@withContext FilePickerResult.Error("No valid files could be read")
    }

    FilePickerResult.Success(
        files = fileInfoList
    )
}

/**
 * Desktop implementation of re-reading file content.
 */
actual suspend fun reReadFileContent(
    absolutePath: String,
    maxContentSize: Long
): ReReadFileResult = withContext(Dispatchers.IO) {
    val file = File(absolutePath)

    // Validate file accessibility
    TextFileReader.validateFile(file)?.let { error ->
        return@withContext ReReadFileResult.Error(error)
    }

    // Read metadata
    val metadata = TextFileReader.readMetadata(file)
        ?: return@withContext ReReadFileResult.Error("Failed to read file metadata")

    // Check size limit
    if (metadata.size > maxContentSize) {
        return@withContext ReReadFileResult.Error(
            "File too large (${metadata.size / 1024} KB). Maximum size is ${maxContentSize / 1024} KB."
        )
    }

    // Read content
    when (val result = TextFileReader.readTextContent(file, metadata.size, maxContentSize)) {
        is TextContentResult.Success -> ReReadFileResult.Success(
            content = result.content,
            fileSize = metadata.size,
            lastModified = metadata.lastModified
        )
        is TextContentResult.Skipped -> ReReadFileResult.Error(result.reason)
        is TextContentResult.Error -> ReReadFileResult.Error(result.message)
    }
}
