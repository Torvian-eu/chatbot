package eu.torvian.chatbot.app.utils.platform

import kotlin.time.Instant

/**
 * Information about a selected file from the file picker.
 *
 * @property absolutePath The absolute path to the file on disk.
 * @property fileName The name of the file (extracted from the path).
 * @property fileSize The size of the file in bytes.
 * @property lastModified The last modification timestamp of the file.
 * @property mimeType The detected MIME type of the file.
 * @property content The UTF-8 text content of the file, or null if content reading is disabled,
 *                   the file is too large, or the file is not a text file.
 */
data class SelectedFileInfo(
    val absolutePath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Instant,
    val mimeType: String,
    val content: String?
)

/**
 * Result of a file selection operation.
 */
sealed class FilePickerResult {
    /**
     * Files were successfully selected.
     *
     * @property files The list of selected files with their metadata.
     */
    data class Success(
        val files: List<SelectedFileInfo>
    ) : FilePickerResult()

    /**
     * User cancelled the file picker dialog.
     */
    data object Cancelled : FilePickerResult()

    /**
     * An error occurred during file selection or reading.
     *
     * @property message A user-friendly error message.
     */
    data class Error(val message: String) : FilePickerResult()
}

/**
 * Opens a platform-specific file picker dialog to select multiple files.
 *
 * The file picker will:
 * - Allow multi-file selection
 * - Read file metadata (size, last modified, MIME type)
 * - Attempt to read UTF-8 text content for text files
 * - Calculate a common base path from selected files
 *
 * This is a suspend function to ensure proper threading on platforms like Desktop (Compose Desktop)
 * where Swing components must be accessed on the Event Dispatch Thread (EDT).
 *
 * @param includeContent Whether to read and include file content for text files.
 *                       Default is true. Set to false for large files or when only
 *                       file references without content are needed.
 * @param maxContentSize Maximum total content size in bytes across all files.
 *                       Files exceeding this limit will have null content.
 *                       Default is 5MB (5 * 1024 * 1024 bytes).
 * @param initialDirectory The initial directory to open the file picker in.
 *                        If null or invalid, the file picker will open in the default directory.
 * @return A [FilePickerResult] indicating success, cancellation, or error.
 */
expect suspend fun selectFiles(
    includeContent: Boolean = true,
    maxContentSize: Long = 5 * 1024 * 1024,
    initialDirectory: String? = null
): FilePickerResult

/**
 * Result of re-reading file content.
 */
sealed class ReReadFileResult {
    /**
     * File content was successfully read.
     *
     * @property content The UTF-8 text content of the file.
     * @property fileSize The updated file size.
     * @property lastModified The updated last modified timestamp.
     */
    data class Success(
        val content: String,
        val fileSize: Long,
        val lastModified: Instant
    ) : ReReadFileResult()

    /**
     * An error occurred while reading the file.
     *
     * @property message A user-friendly error message.
     */
    data class Error(val message: String) : ReReadFileResult()
}

/**
 * Re-reads the content of a file from disk based on its absolute path.
 * Used to toggle content inclusion on after it was previously disabled.
 *
 * This is a suspend function to ensure proper threading on platforms like Desktop (Compose Desktop)
 * where blocking I/O should not occur on the main thread.
 *
 * @param absolutePath The absolute path to the file.
 * @param maxContentSize Maximum content size in bytes for the file.
 *                       Default is 5MB (5 * 1024 * 1024 bytes).
 * @return A [ReReadFileResult] indicating success or error.
 */
expect suspend fun reReadFileContent(
    absolutePath: String,
    maxContentSize: Long = 5 * 1024 * 1024
): ReReadFileResult

/**
 * Utility object for MIME type detection based on file extensions.
 */
object MimeTypeDetector {

    private val extensionToMimeType = mapOf(
        // Text files
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "log" to "text/plain",
        "ini" to "text/plain",
        "cfg" to "text/plain",
        "conf" to "text/plain",

        // Programming languages
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "java" to "text/x-java-source",
        "py" to "text/x-python",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "ts" to "text/typescript",
        "tsx" to "text/typescript",
        "jsx" to "text/javascript",
        "c" to "text/x-c",
        "cpp" to "text/x-c++",
        "h" to "text/x-c",
        "hpp" to "text/x-c++",
        "cs" to "text/x-csharp",
        "go" to "text/x-go",
        "rs" to "text/x-rust",
        "rb" to "text/x-ruby",
        "php" to "text/x-php",
        "swift" to "text/x-swift",
        "scala" to "text/x-scala",
        "groovy" to "text/x-groovy",
        "r" to "text/x-r",
        "sql" to "text/x-sql",
        "sh" to "text/x-shellscript",
        "bash" to "text/x-shellscript",
        "zsh" to "text/x-shellscript",
        "ps1" to "text/x-powershell",
        "bat" to "text/x-batch",
        "cmd" to "text/x-batch",

        // Web files
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "scss" to "text/x-scss",
        "sass" to "text/x-sass",
        "less" to "text/x-less",

        // Data formats
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "toml" to "text/x-toml",
        "properties" to "text/x-java-properties",

        // Documentation
        "rst" to "text/x-rst",
        "tex" to "text/x-tex",
        "latex" to "text/x-latex",

        // Configuration
        "gradle" to "text/x-gradle",
        "dockerfile" to "text/x-dockerfile",
        "makefile" to "text/x-makefile",
        "cmake" to "text/x-cmake",

        // Binary (for reference - these won't have content read)
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "jar" to "application/java-archive",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "exe" to "application/x-msdownload",
        "dll" to "application/x-msdownload",
        "so" to "application/x-sharedlib",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "wav" to "audio/wav",
        "avi" to "video/x-msvideo"
    )

    /**
     * Known binary file extensions that should never be read as text.
     */
    private val knownBinaryExtensions = setOf(
        "pdf", "zip", "jar", "tar", "gz", "exe", "dll", "so",
        "png", "jpg", "jpeg", "gif", "ico",
        "mp3", "mp4", "wav", "avi",
        "bin", "obj", "o", "a", "lib", "class"
    )

    /**
     * Detects the MIME type based on the file extension.
     *
     * @param fileName The file name or path.
     * @return The detected MIME type, or "application/octet-stream" if unknown.
     */
    fun detectMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extensionToMimeType[extension] ?: "application/octet-stream"
    }

    /**
     * Checks if the MIME type indicates a text-based file that can be read as UTF-8.
     *
     * @param mimeType The MIME type to check.
     * @return true if the file is likely a text file, false otherwise.
     */
    fun isTextMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/xml" ||
                mimeType == "application/javascript" ||
                mimeType == "application/typescript" ||
                mimeType.contains("+xml") ||
                mimeType.contains("+json")
    }

    /**
     * Checks if a file extension is known to be binary and should not be read as text.
     *
     * @param fileName The file name or path.
     * @return true if the file is a known binary type, false otherwise.
     */
    fun isKnownBinaryFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in knownBinaryExtensions
    }
}

