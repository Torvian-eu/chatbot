package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.core.FileReference
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies file-reference embedding behavior exposed by [DefaultFileReferenceContentBuilder].
 */
class DefaultFileReferenceContentBuilderTest {
    /**
     * Verifies inline references are inserted in descending position order so offsets remain stable.
     */
    @Test
    fun `build inserts inline references without disturbing earlier offsets`() {
        val builder = DefaultFileReferenceContentBuilder()
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        val result = builder.build(
            content = "Hello world",
            fileReferences = listOf(
                FileReference(
                    basePath = "C:/workspace",
                    relativePath = "docs/inline.txt",
                    fileSize = 12,
                    lastModified = modifiedAt,
                    mimeType = "text/plain",
                    content = "INLINE",
                    inlinePosition = 6
                ),
                FileReference(
                    basePath = "C:/workspace",
                    relativePath = "docs/ref.json",
                    fileSize = 2048,
                    lastModified = modifiedAt,
                    mimeType = "application/json",
                    content = null,
                    inlinePosition = 0
                )
            )
        )

        val timestamp = modifiedAt.toLocalDateTime(TimeZone.currentSystemDefault())
        val expectedTimestamp = "${timestamp.date} ${timestamp.hour.toString().padStart(2, '0')}:${timestamp.minute.toString().padStart(2, '0')}"

        assertEquals(
            "\n[reference: docs/ref.json (2 KB, application/json)]\nHello \n--- docs/inline.txt [12 B, text/plain, $expectedTimestamp] ---\nINLINE\n--- end inline.txt ---\nworld",
            result
        )
    }

    /**
     * Verifies non-inline references are appended under the attached-files section.
     */
    @Test
    fun `build appends non inline references at the end`() {
        val builder = DefaultFileReferenceContentBuilder()
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val timestamp = modifiedAt.toLocalDateTime(TimeZone.currentSystemDefault())
        val expectedTimestamp = "${timestamp.date} ${timestamp.hour.toString().padStart(2, '0')}:${timestamp.minute.toString().padStart(2, '0')}"

        val result = builder.build(
            content = "Question",
            fileReferences = listOf(
                FileReference(
                    basePath = "C:/workspace",
                    relativePath = "docs/guide.md",
                    fileSize = 1536,
                    lastModified = modifiedAt,
                    mimeType = "text/markdown",
                    content = "# Guide",
                    inlinePosition = null
                ),
                FileReference(
                    basePath = "C:/workspace",
                    relativePath = "images/logo.png",
                    fileSize = 99,
                    lastModified = modifiedAt,
                    mimeType = "image/png",
                    content = null,
                    inlinePosition = null
                )
            )
        )

        assertEquals(
            "Question\n\n--- Attached Files ---\n\n--- docs/guide.md [1 KB, text/markdown, $expectedTimestamp] ---\n# Guide\n--- end guide.md ---\n[reference: images/logo.png (99 B, image/png)]",
            result
        )
    }
}