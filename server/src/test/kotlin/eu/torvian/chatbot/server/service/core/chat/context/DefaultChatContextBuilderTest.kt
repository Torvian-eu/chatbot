package eu.torvian.chatbot.server.service.core.chat.context

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies threaded context reconstruction performed by [DefaultChatContextBuilder].
 */
class DefaultChatContextBuilderTest {
    /**
     * Verifies the builder follows only the selected branch and reconstructs completed tool results.
     */
    @Test
    fun `buildContext reconstructs branch with embedded file references and completed tool results`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val userMessage = ChatMessage.UserMessage(
            id = 1L,
            sessionId = 44L,
            content = "Question",
            createdAt = modifiedAt,
            updatedAt = modifiedAt,
            parentMessageId = null,
            childrenMessageIds = listOf(2L),
            fileReferences = listOf(
                FileReference(
                    basePath = "C:/workspace",
                    relativePath = "docs/context.txt",
                    fileSize = 4,
                    lastModified = modifiedAt,
                    mimeType = "text/plain",
                    content = "DATA",
                    inlinePosition = null
                )
            )
        )
        val assistantWithToolCall = ChatMessage.AssistantMessage(
            id = 2L,
            sessionId = 44L,
            content = "",
            createdAt = modifiedAt,
            updatedAt = modifiedAt,
            parentMessageId = 1L,
            childrenMessageIds = listOf(3L, 4L),
            fileReferences = emptyList(),
            modelId = 5L,
            settingsId = 6L
        )
        val branchUserMessage = ChatMessage.UserMessage(
            id = 3L,
            sessionId = 44L,
            content = "Follow-up",
            createdAt = modifiedAt,
            updatedAt = modifiedAt,
            parentMessageId = 2L,
            childrenMessageIds = emptyList()
        )
        val siblingBranchMessage = ChatMessage.UserMessage(
            id = 4L,
            sessionId = 44L,
            content = "Sibling branch",
            createdAt = modifiedAt,
            updatedAt = modifiedAt,
            parentMessageId = 2L,
            childrenMessageIds = emptyList()
        )

        val toolCalls = listOf(
            ToolCall(
                id = 11L,
                messageId = 2L,
                toolDefinitionId = 8L,
                toolName = "search",
                toolCallId = "call-success",
                input = "{\"query\":\"docs\"}",
                output = "{\"results\":[]}",
                status = ToolCallStatus.SUCCESS,
                executedAt = modifiedAt
            ),
            ToolCall(
                id = 12L,
                messageId = 2L,
                toolDefinitionId = 8L,
                toolName = "search",
                toolCallId = "call-pending",
                input = "{\"query\":\"later\"}",
                output = null,
                status = ToolCallStatus.PENDING,
                executedAt = modifiedAt
            )
        )

        val context = builder.buildContext(
            startingMessageId = 3L,
            sessionMessages = listOf(siblingBranchMessage, branchUserMessage, assistantWithToolCall, userMessage),
            toolCalls = toolCalls
        )

        assertEquals(4, context.size)
        assertEquals(RawChatMessage.User::class, context[0]::class)
        assertEquals(RawChatMessage.Assistant::class, context[1]::class)
        assertEquals(RawChatMessage.Tool::class, context[2]::class)
        assertEquals(RawChatMessage.User::class, context[3]::class)

        assertEquals(true, context[0].content?.contains("--- Attached Files ---"))
        assertEquals(
            listOf("call-success", "call-pending"),
            (context[1] as RawChatMessage.Assistant).toolCalls?.map { it.id }
        )
        assertEquals("{\"results\":[]}", context[2].content)
        assertEquals("Follow-up", context[3].content)
    }
}