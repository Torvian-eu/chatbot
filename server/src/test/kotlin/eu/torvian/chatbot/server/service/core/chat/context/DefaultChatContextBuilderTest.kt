package eu.torvian.chatbot.server.service.core.chat.context

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            ),
            ToolCall(
                id = 13L,
                messageId = 2L,
                toolDefinitionId = 8L,
                toolName = "search",
                toolCallId = "call-invalid",
                input = null,
                output = "invalid arguments",
                status = ToolCallStatus.ERROR,
                executedAt = modifiedAt
            ),
            ToolCall(
                id = 15L,
                messageId = 2L,
                toolDefinitionId = 8L,
                toolName = "search",
                toolCallId = "call-cancelled",
                input = "{\"query\":\"stopped\"}",
                output = null,
                status = ToolCallStatus.CANCELLED,
                executedAt = modifiedAt
            ),
            ToolCall(
                id = 14L,
                messageId = 2L,
                toolDefinitionId = 8L,
                toolName = "ping",
                toolCallId = "call-parameterless",
                input = null,
                output = "pong",
                status = ToolCallStatus.SUCCESS,
                executedAt = modifiedAt
            )
        )

        val context = builder.buildContext(
            startingMessageId = 3L,
            sessionMessages = listOf(siblingBranchMessage, branchUserMessage, assistantWithToolCall, userMessage),
            toolCalls = toolCalls
        )

        assertEquals(8, context.size)
        assertEquals(RawChatMessage.User::class, context[0]::class)
        assertEquals(RawChatMessage.Assistant::class, context[1]::class)
        assertEquals(RawChatMessage.Tool::class, context[2]::class)
        assertEquals(RawChatMessage.Tool::class, context[3]::class)
        assertEquals(RawChatMessage.Tool::class, context[4]::class)
        assertEquals(RawChatMessage.Tool::class, context[5]::class)
        assertEquals(RawChatMessage.Tool::class, context[6]::class)
        assertEquals(RawChatMessage.User::class, context[7]::class)

        assertEquals(true, context[0].content?.contains("--- Attached Files ---"))
        assertEquals(
            listOf("call-success", "call-pending", "call-invalid", "call-parameterless", "call-cancelled"),
            (context[1] as RawChatMessage.Assistant).toolCalls?.map { it.id }
        )
        assertEquals("{\"results\":[]}", context[2].content)
        assertEquals("{\"cancelled\":\"Tool call was cancelled before a result was produced.\"}", context[3].content)
        assertEquals("{\"error\":\"Unknown error\",\"output\":\"invalid arguments\"}", context[4].content)
        assertEquals("pong", context[5].content)
        assertEquals("{\"cancelled\":\"Tool call was cancelled before a result was produced.\"}", context[6].content)
        assertEquals("Follow-up", context[7].content)
        assertEquals(
            listOf("call-success", "call-pending", "call-invalid", "call-parameterless", "call-cancelled"),
            context.filterIsInstance<RawChatMessage.Tool>().map { it.toolCallId }
        )
    }

    /**
     * Verifies persisted reasoning items are reconstructed onto the matching assistant message.
     */
    @Test
    fun `buildContext reconstructs reasoning items onto the assistant message`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("encrypted_content", "opaque")
            }
        )
        val userMessage = ChatMessage.UserMessage(
            id = 1L,
            sessionId = 44L,
            content = "Question",
            createdAt = modifiedAt,
            updatedAt = modifiedAt,
            parentMessageId = null,
            childrenMessageIds = listOf(2L)
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 2L,
            sessionId = 44L,
            content = "Answer",
            createdAt = modifiedAt,
            updatedAt = modifiedAt,
            parentMessageId = 1L,
            childrenMessageIds = emptyList(),
            modelId = 5L,
            settingsId = 6L,
            reasoningItems = reasoningItems
        )

        val context = builder.buildContext(
            startingMessageId = 2L,
            sessionMessages = listOf(userMessage, assistantMessage),
            toolCalls = emptyList()
        )

        assertEquals(2, context.size)
        val reconstructedAssistant = context[1] as RawChatMessage.Assistant
        assertNotNull(reconstructedAssistant.reasoningItems)
        assertEquals(1, reconstructedAssistant.reasoningItems.size)
        assertEquals("opaque", reconstructedAssistant.reasoningItems[0]["encrypted_content"]?.jsonPrimitive?.content)
        // The source model id is carried onto the raw message so replay can gate encrypted payloads.
        assertEquals(5L, reconstructedAssistant.reasoningModelId)
    }
}