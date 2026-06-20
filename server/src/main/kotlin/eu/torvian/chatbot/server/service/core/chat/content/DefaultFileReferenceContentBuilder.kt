package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.core.FileReference
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.time.Instant

/**
 * Default [FileReferenceContentBuilder] that preserves the existing file-reference rendering format.
 */
class DefaultFileReferenceContentBuilder : FileReferenceContentBuilder {
    /**
     * Embeds inline references in position order and appends non-inline attachments to the end.
     *
     * @param content Original user-message content.
     * @param fileReferences File references attached to the user message.
     * @return Message content with file references embedded.
     */
    override fun build(content: String, fileReferences: List<FileReference>): String {
        if (fileReferences.isEmpty()) return content

        val inlineRefs = fileReferences.filter { it.isInline }.sortedByDescending { it.inlinePosition ?: 0 }
        val nonInlineRefs = fileReferences.filter { !it.isInline }

        var result = content

        for (ref in inlineRefs) {
            val position = ref.inlinePosition ?: continue
            val insertion = if (ref.content != null) {
                "\n${formatFileHeader(ref)}\n${ref.content}\n--- end ${ref.fileName} ---\n"
            } else {
                "\n${formatFileReference(ref)}\n"
            }

            // Descending insertion keeps earlier offsets stable when multiple inline references exist.
            val insertPos = position.coerceIn(0, result.length)
            result = result.take(insertPos) + insertion + result.substring(insertPos)
        }

        if (nonInlineRefs.isNotEmpty()) {
            result += "\n\n--- Attached Files ---"

            for (ref in nonInlineRefs) {
                result += if (ref.content != null) {
                    "\n\n${formatFileHeader(ref)}\n${ref.content}\n--- end ${ref.fileName} ---"
                } else {
                    "\n${formatFileReference(ref)}"
                }
            }
        }

        return result
    }

    /**
     * Formats the header used when file contents are embedded into chat context.
     *
     * @param ref File reference whose metadata should be rendered.
     * @return Header line describing the embedded file content.
     */
    private fun formatFileHeader(ref: FileReference): String {
        return buildString {
            append("--- ${ref.relativePath}")
            append(" [${formatFileSize(ref.fileSize)}, ${ref.mimeType}, ${formatLastModified(ref.lastModified)}]")
            append(" ---")
        }
    }

    /**
     * Formats a metadata-only reference when file contents are not embedded.
     *
     * @param ref File reference whose metadata should be rendered.
     * @return Reference marker describing the attached file.
     */
    private fun formatFileReference(ref: FileReference): String {
        return "[reference: ${ref.relativePath} (${formatFileSize(ref.fileSize)}, ${ref.mimeType})]"
    }

    /**
     * Formats a byte count using the server's existing human-readable thresholds.
     *
     * @param bytes File size in bytes.
     * @return Human-readable size string.
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0))} MB"
            else -> "${String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    /**
     * Formats a timestamp using the current system time zone for display in attachment metadata.
     *
     * @param instant File modification time.
     * @return Human-readable local date/time string.
     */
    private fun formatLastModified(instant: Instant): String {
        val tz = TimeZone.currentSystemDefault()
        val localDateTime = instant.toLocalDateTime(tz)
        return "${localDateTime.date} ${
            String.format(
                Locale.ROOT,
                "%02d:%02d",
                localDateTime.hour,
                localDateTime.minute
            )
        }"
    }
}