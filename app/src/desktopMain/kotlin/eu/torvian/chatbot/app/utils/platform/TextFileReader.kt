package eu.torvian.chatbot.app.utils.platform

import kotlinx.datetime.Instant
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Maximum size for a single file's content (1 MB).
 */
private const val MAX_SINGLE_FILE_SIZE: Long = 1024 * 1024

/**
 * Result of reading file metadata.
 */
internal data class FileMetadata(
    val file: File,
    val size: Long,
    val lastModified: Instant,
    val mimeType: String
)

/**
 * Result of attempting to read text content from a file.
 */
internal sealed class TextContentResult {
    data class Success(val content: String) : TextContentResult()
    data class Skipped(val reason: String) : TextContentResult()
    data class Error(val message: String) : TextContentResult()
}

/**
 * Utility object for reading text files with validation.
 * All methods in this object perform blocking I/O and should be called from Dispatchers.IO.
 */
internal object TextFileReader {

    /**
     * Reads file metadata (size, last modified, MIME type).
     *
     * @param file The file to read metadata from.
     * @return FileMetadata if successful, null if the file doesn't exist or isn't a regular file.
     */
    fun readMetadata(file: File): FileMetadata? {
        if (!file.exists() || !file.isFile) return null

        return try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            FileMetadata(
                file = file,
                size = attributes.size(),
                lastModified = Instant.fromEpochMilliseconds(attributes.lastModifiedTime().toMillis()),
                mimeType = MimeTypeDetector.detectMimeType(file.name)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads text content from a file with size limit validation.
     *
     * @param file The file to read.
     * @param fileSize The known file size (to avoid re-reading attributes).
     * @param maxSize Maximum allowed file size. Defaults to [MAX_SINGLE_FILE_SIZE].
     * @return TextContentResult indicating success, skip, or error.
     */
    fun readTextContent(
        file: File,
        fileSize: Long,
        maxSize: Long = MAX_SINGLE_FILE_SIZE
    ): TextContentResult {
        if (fileSize > maxSize) {
            return TextContentResult.Skipped("File too large (${fileSize / 1024} KB)")
        }

        return try {
            val bytes = Files.readAllBytes(file.toPath())

            // Check for null bytes (binary file indicator)
            if (bytes.any { it == 0.toByte() }) {
                return TextContentResult.Skipped("File appears to be binary")
            }

            // Decode as UTF-8
            val content = String(bytes, StandardCharsets.UTF_8)

            // Check for replacement characters indicating invalid UTF-8 sequences
            if (content.contains('\uFFFD')) {
                return TextContentResult.Skipped("File contains invalid UTF-8 characters")
            }

            TextContentResult.Success(content)
        } catch (e: Exception) {
            TextContentResult.Error("Failed to read file: ${e.message}")
        }
    }

    /**
     * Reads text content only if the file is a text type and within size limits.
     * Unknown file types (not in known binary list) will be attempted to be read.
     *
     * @param metadata The file metadata.
     * @param remainingQuota Remaining size quota for content reading.
     * @return The content string if successful, null otherwise.
     */
    fun readTextContentIfEligible(
        metadata: FileMetadata,
        remainingQuota: Long
    ): String? {
        // Skip known binary files
        if (MimeTypeDetector.isKnownBinaryFile(metadata.file.name)) {
            return null
        }

        // Skip if file exceeds remaining quota
        if (metadata.size > remainingQuota) {
            return null
        }

        // Skip files larger than the single file limit
        if (metadata.size > MAX_SINGLE_FILE_SIZE) {
            return null
        }

        return when (val result = readTextContent(metadata.file, metadata.size)) {
            is TextContentResult.Success -> result.content
            is TextContentResult.Skipped -> null
            is TextContentResult.Error -> null
        }
    }

    /**
     * Validates that a file exists, is readable, and is a regular file.
     *
     * @param file The file to validate.
     * @return An error message if validation fails, null if the file is valid.
     */
    fun validateFile(file: File): String? {
        return when {
            !file.exists() -> "File not found: ${file.name}"
            !file.isFile -> "Path is not a file: ${file.name}"
            !file.canRead() -> "File is not readable: ${file.name}"
            else -> null
        }
    }
}

