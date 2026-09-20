package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the wire contract of the assistant message completion fields.
 *
 * A payload produced before the fields existed (no `isComplete`/`incompleteCause`/`errorCode`/`errorMessage`
 * keys) must decode as *completed*, and a terminal payload must round-trip cause, code and bounded reason while
 * [ChatMessage.AssistantMessage.showsIncompleteNotice] reflects exactly the four documented combinations
 * (NFR-2, RC-2).
 */
class ChatMessageAssistantMessageCompletionSerializationTest {

    /**
     * Serializer configuration matching the server's payload settings: default properties are encoded, while
     * decoding stays strict (`ignoreUnknownKeys = false`), which is how the app's own `Json` behaves.
     */
    private val json = Json {
        encodeDefaults = true
    }

    /** Completion keys added to the wire contract by this feature. */
    private val completionKeys = setOf("isComplete", "incompleteCause", "errorCode", "errorMessage")

    @Test
    fun `pre-change payload without completion keys decodes as completed`() {
        // Reproduce an old payload: the current wire shape with the new keys removed, exactly as a server build
        // predating the feature would have serialized it.
        val encodedMessage = json.encodeToJsonElement(ChatMessage.serializer(), assistantMessage()).jsonObject
        val preChangePayload = JsonObject(encodedMessage.filterKeys { it !in completionKeys })
        assertTrue(
            completionKeys.none { preChangePayload.containsKey(it) },
            "The simulated pre-change payload must not contain the new keys: $preChangePayload"
        )

        val decoded = assertIs<ChatMessage.AssistantMessage>(
            json.decodeFromString(ChatMessage.serializer(), preChangePayload.toString())
        )

        assertTrue(decoded.isComplete, "A payload without the completion flag must decode as completed")
        assertNull(decoded.incompleteCause)
        assertNull(decoded.errorCode)
        assertNull(decoded.errorMessage)
        assertFalse(decoded.showsIncompleteNotice)
    }

    @Test
    fun `failed payload round-trips cause code and bounded reason`() {
        val failed = assistantMessage(
            isComplete = false,
            incompleteCause = AssistantMessageIncompleteCause.FAILED,
            errorCode = AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED,
            errorMessage = "The response was stopped because it exceeded the 64,000-character limit."
        )

        val wire = json.encodeToString(ChatMessage.serializer(), failed)
        assertTrue(wire.contains("\"isComplete\":false"))
        assertTrue(wire.contains("\"incompleteCause\":\"FAILED\""))
        assertTrue(wire.contains("\"errorCode\":\"OUTPUT_LIMIT_EXCEEDED\""))

        val decoded = assertIs<ChatMessage.AssistantMessage>(
            json.decodeFromString(ChatMessage.serializer(), wire)
        )
        assertEquals(failed, decoded)
        assertTrue(decoded.showsIncompleteNotice)
    }

    @Test
    fun `user interrupted payload round-trips without a reason`() {
        val interrupted = assistantMessage(
            content = "Partial answer",
            isComplete = false,
            incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER
        )

        val wire = json.encodeToString(ChatMessage.serializer(), interrupted)
        assertTrue(wire.contains("\"incompleteCause\":\"INTERRUPTED_BY_USER\""))

        val decoded = assertIs<ChatMessage.AssistantMessage>(
            json.decodeFromString(ChatMessage.serializer(), wire)
        )
        assertEquals(interrupted, decoded)
        assertNull(decoded.errorCode, "A user interruption must not carry an error code")
        assertNull(decoded.errorMessage, "A user interruption must not carry a reason")
        assertTrue(decoded.showsIncompleteNotice)
    }

    @Test
    fun `notice is shown only for a not completed message with a terminal cause`() {
        val completed = assistantMessage()
        val inFlight = assistantMessage(isComplete = false)
        val interrupted = assistantMessage(
            isComplete = false,
            incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER
        )
        val failed = assistantMessage(
            isComplete = false,
            incompleteCause = AssistantMessageIncompleteCause.FAILED,
            errorCode = AssistantMessageErrorCode.STREAM_INTERRUPTED,
            errorMessage = "The response stream ended before it was completed."
        )

        // FR-11/FR-15: a completed message and an in-flight placeholder (no cause) stay silent.
        assertFalse(completed.showsIncompleteNotice)
        assertFalse(inFlight.showsIncompleteNotice)
        assertTrue(interrupted.showsIncompleteNotice)
        assertTrue(failed.showsIncompleteNotice)
    }

    @Test
    fun `enum names are the persisted and wire contract`() {
        assertEquals(
            listOf("INTERRUPTED_BY_USER", "FAILED"),
            AssistantMessageIncompleteCause.entries.map { it.name }
        )
        assertEquals(
            setOf(
                "AUTHENTICATION_FAILED",
                "RATE_LIMITED",
                "PROVIDER_REQUEST_REJECTED",
                "PROVIDER_UNAVAILABLE",
                "INVALID_PROVIDER_RESPONSE",
                "CONFIGURATION_ERROR",
                "OUTPUT_LIMIT_EXCEEDED",
                "PROVIDER_OUTPUT_LIMIT_EXCEEDED",
                "TOOL_CALL_ITERATION_LIMIT_EXCEEDED",
                "TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED",
                "TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED",
                "STREAM_INTERRUPTED",
                "UNEXPECTED_ERROR"
            ),
            AssistantMessageErrorCode.entries.map { it.name }.toSet()
        )

        assertEquals(
            AssistantMessageIncompleteCause.FAILED,
            Json.decodeFromString<AssistantMessageIncompleteCause>("\"FAILED\"")
        )
        assertEquals(
            AssistantMessageErrorCode.STREAM_INTERRUPTED,
            Json.decodeFromString<AssistantMessageErrorCode>("\"STREAM_INTERRUPTED\"")
        )
    }

    /**
     * Builds an assistant message with sensible defaults so each test only states the field it verifies.
     *
     * @param content Message content, empty for a message with no generated text.
     * @param isComplete Completion flag; `true` (completed) unless a test overrides it.
     * @param incompleteCause Terminal incompletion cause, or `null` when none is recorded.
     * @param errorCode Failure classification, normally only set with cause `FAILED`.
     * @param errorMessage Bounded failure reason, normally only set with cause `FAILED`.
     * @return The assistant message under test.
     */
    private fun assistantMessage(
        content: String = "",
        isComplete: Boolean = true,
        incompleteCause: AssistantMessageIncompleteCause? = null,
        errorCode: AssistantMessageErrorCode? = null,
        errorMessage: String? = null
    ): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
        id = 42L,
        sessionId = 7L,
        content = content,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        parentMessageId = null,
        modelId = 1L,
        settingsId = 2L,
        isComplete = isComplete,
        incompleteCause = incompleteCause,
        errorCode = errorCode,
        errorMessage = errorMessage
    )
}
