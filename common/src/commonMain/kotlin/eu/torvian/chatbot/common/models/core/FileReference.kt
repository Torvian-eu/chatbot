package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents a reference to a file that can be attached to a chat message.
 *
 * File references allow users to attach files to messages, either as simple references
 * (file path only) or with the actual file content included.
 *
 * @property basePath The base path used for resolving relative paths. Not sent to the LLM.
 * @property relativePath The path relative to basePath. This is what's sent to the LLM.
 * @property fileSize The size of the file in bytes.
 * @property lastModified The last modification timestamp of the file.
 * @property mimeType The MIME type of the file (e.g., "text/plain", "application/json").
 * @property content Optional file content. When present, the content is included in the LLM context.
 *                   Only supported for text-based files.
 * @property inlinePosition Optional position in the message where this reference should be inserted inline.
 *                          When null, the reference is appended at the end of the message.
 */
@Serializable
data class FileReference(
    val basePath: String,
    val relativePath: String,
    val fileSize: Long,
    val lastModified: Instant,
    val mimeType: String,
    val content: String? = null,
    val inlinePosition: Int? = null
) {
    /**
     * The file name extracted from the relative path.
     */
    val fileName: String
        get() = relativePath.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Whether this reference is placed inline in the message content.
     */
    val isInline: Boolean
        get() = inlinePosition != null

    /**
     * The full path to the file (basePath + relativePath).
     */
    val fullPath: String
        get() = if (basePath.endsWith('/') || basePath.endsWith('\\')) {
            basePath + relativePath
        } else {
            "$basePath/$relativePath"
        }
}

