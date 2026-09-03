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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
        ).flatten()

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
        ).flatten()

        assertEquals(2, context.size)
        val reconstructedAssistant = context[1] as RawChatMessage.Assistant
        assertNotNull(reconstructedAssistant.reasoningItems)
        assertEquals(1, reconstructedAssistant.reasoningItems.size)
        assertEquals("opaque", reconstructedAssistant.reasoningItems[0]["encrypted_content"]?.jsonPrimitive?.content)
        // The source model id is carried onto the raw message so replay can gate encrypted payloads.
        assertEquals(5L, reconstructedAssistant.reasoningModelId)
    }

    /**
     * Verifies the identity-bearing units carry ordered source IDs and timestamps and that an
     * assistant source plus its tool results stay inside one unit.
     */
    @Test
    fun `buildContext returns one identity unit per source with assistant and results together`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val userMessage = ChatMessage.UserMessage(
            id = 1L, sessionId = 44L, content = "Question", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = null, childrenMessageIds = listOf(2L)
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 2L, sessionId = 44L, content = "", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = 1L, childrenMessageIds = emptyList(), modelId = 5L, settingsId = 6L
        )
        val toolCalls = listOf(
            ToolCall(
                id = 11L, messageId = 2L, toolDefinitionId = 8L, toolName = "search",
                toolCallId = "call-1", input = "{}", output = "{\"ok\":true}",
                status = ToolCallStatus.SUCCESS, executedAt = modifiedAt
            ),
            ToolCall(
                id = 12L, messageId = 2L, toolDefinitionId = 8L, toolName = "search",
                toolCallId = "call-2", input = "{}", output = "{\"ok\":false}",
                status = ToolCallStatus.SUCCESS, executedAt = modifiedAt
            )
        )

        val context = builder.buildContext(
            startingMessageId = 2L,
            sessionMessages = listOf(userMessage, assistantMessage),
            toolCalls = toolCalls
        )

        assertEquals(2, context.units.size)
        assertEquals(listOf(1L, 2L), context.units.map { it.source.id })
        assertEquals(listOf(modifiedAt, modifiedAt), context.units.map { it.source.updatedAt })
        assertEquals(1, context.units[0].rawMessages.size)
        // The assistant source expands into assistant + both tool results inside ONE unit, so a
        // compaction replacement boundary can never split the call from its results.
        assertEquals(3, context.units[1].rawMessages.size)
        assertIs<RawChatMessage.Assistant>(context.units[1].rawMessages[0])
        assertEquals(2, context.units[1].rawMessages.filterIsInstance<RawChatMessage.Tool>().size)
    }

    /**
     * Verifies a missing starting message is rejected instead of returning a partial chain.
     */
    @Test
    fun `buildContext rejects a missing starting message`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val userMessage = ChatMessage.UserMessage(
            id = 1L, sessionId = 44L, content = "Question", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = null, childrenMessageIds = emptyList()
        )

        assertFailsWith<IllegalStateException> {
            builder.buildContext(
                startingMessageId = 99L,
                sessionMessages = listOf(userMessage),
                toolCalls = emptyList()
            )
        }
    }

    /**
     * Verifies a broken parent link is rejected instead of returning a partial chain.
     */
    @Test
    fun `buildContext rejects a broken parent link`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        // Child references a parent that is absent from the message set.
        val child = ChatMessage.UserMessage(
            id = 2L, sessionId = 44L, content = "Reply", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = 1L, childrenMessageIds = emptyList()
        )

        assertFailsWith<IllegalStateException> {
            builder.buildContext(
                startingMessageId = 2L,
                sessionMessages = listOf(child),
                toolCalls = emptyList()
            )
        }
    }

    /**
     * Verifies a cyclic parent chain is rejected instead of silently truncating the thread.
     */
    @Test
    fun `buildContext rejects a cyclic parent chain`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val a = ChatMessage.UserMessage(
            id = 1L, sessionId = 44L, content = "A", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = 2L, childrenMessageIds = emptyList()
        )
        val b = ChatMessage.UserMessage(
            id = 2L, sessionId = 44L, content = "B", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = 1L, childrenMessageIds = emptyList()
        )

        assertFailsWith<IllegalStateException> {
            builder.buildContext(
                startingMessageId = 1L,
                sessionMessages = listOf(a, b),
                toolCalls = emptyList()
            )
        }
    }

    /**
     * Verifies mixed-session source chains are rejected so coverage can never cross sessions.
     */
    @Test
    fun `buildContext rejects mixed-session chains`() {
        val builder = DefaultChatContextBuilder(
            fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
            toolResultContentBuilder = DefaultToolResultContentBuilder()
        )
        val modifiedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val root = ChatMessage.UserMessage(
            id = 1L, sessionId = 44L, content = "Root", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = null, childrenMessageIds = listOf(2L)
        )
        // Child belongs to a different session than its parent.
        val foreignChild = ChatMessage.UserMessage(
            id = 2L, sessionId = 45L, content = "Foreign", createdAt = modifiedAt, updatedAt = modifiedAt,
            parentMessageId = 1L, childrenMessageIds = emptyList()
        )

        assertFailsWith<IllegalStateException> {
            builder.buildContext(
                startingMessageId = 2L,
                sessionMessages = listOf(root, foreignChild),
                toolCalls = emptyList()
            )
        }
    }
}