package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.SignedBuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.builtin.BuiltInToolAuthorizationValidationResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolAuthorizationValidator
import eu.torvian.chatbot.worker.builtin.BuiltInToolCallExecutor
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolCallInteraction].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolCallInteractionTest {

    /**
     * Verifies that the executor is invoked with the parsed tool name and input, and that the
     * resulting [BuiltInToolExecutionResult] is sent back as a `command.result` payload.
     */
    @Test
    fun `direct tool call dispatches the executor and returns a command result`() = runTest {
        val emitter = RecordingEmitter()
        val input = buildJsonObject {
            put("path", "notes.md")
        }
        val authorization = buildAuthorization(toolName = "read_text_file")
        val signedRequest = SignedBuiltInToolExecutionRequest(
            signedRequest = signedRequestFor(authorization, input)
        )
        val requestPayload = signedRequest.toWorkerCommandRequestPayload()
            .getOrElse { error("Failed to build request payload for test: $it") }

        val executor = RecordingExecutor(
            result = BuiltInToolExecutionResult(
                output = "Hello, world!",
                isError = false,
            )
        )
        val interaction = ToolCallInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-1",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = "int-1",
                payload = null,
            ),
            requestPayload = requestPayload,
            authorizationValidator = AuthorizingValidator(authorization),
            toolCallExecutor = executor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider(),
        )

        interaction.start()

        assertEquals(1, executor.executed.size, "Executor should be invoked exactly once")
        assertEquals("read_text_file", executor.executed.single().toolName)
        val executedInput = executor.executed.single().input
        val debugPath = executedInput["path"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        println("DEBUG executedInput=$executedInput  debugPath=$debugPath inputEncoding=${input.toString()}")
        assertEquals("notes.md", debugPath)

        val outbound = emitter.messages
        assertEquals(2, outbound.size, "Expected accepted + result")
        assertEquals(WorkerProtocolMessageTypes.COMMAND_ACCEPTED, outbound[0].type)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_RESULT, outbound[1].type)

        val resultPayload = decodeProtocolPayload<WorkerCommandResultPayload>(
            outbound[1].payload!!,
            "WorkerCommandResultPayload",
        ).getOrElse { error("Expected result payload to decode: $it") }
        assertEquals(WorkerCommandResultStatuses.SUCCESS, resultPayload.status)

        val result = decodeProtocolPayload<BuiltInToolExecutionResult>(
            resultPayload.data,
            "BuiltInToolExecutionResult",
        ).getOrElse { error("Expected built-in result to decode: $it") }
        assertEquals("Hello, world!", result.output)
        assertEquals(false, result.isError)
    }

    /**
     * Verifies that an authorization rejection is translated into a `command.result` whose
     * `isError = true` and carries the rejection code.
     */
    @Test
    fun `rejected authorization yields an error result without invoking the executor`() = runTest {
        val emitter = RecordingEmitter()
        val authorization = buildAuthorization(toolName = "read_text_file")
        val signedRequest = SignedBuiltInToolExecutionRequest(
            signedRequest = signedRequestFor(
                authorization,
                buildJsonObject { put("path", "x") }
            )
        )
        val requestPayload = signedRequest.toWorkerCommandRequestPayload()
            .getOrElse { error("Failed to build request payload for test: $it") }

        val executor = RecordingExecutor(
            result = BuiltInToolExecutionResult(output = "unused")
        )
        val interaction = ToolCallInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-2",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = "int-2",
                payload = null,
            ),
            requestPayload = requestPayload,
            authorizationValidator = RejectingValidator(
                code = "invalid_signature",
                message = "Bad signature",
            ),
            toolCallExecutor = executor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider(),
        )

        interaction.start()

        assertTrue(executor.executed.isEmpty(), "Executor should not run on rejected auth")
        val outbound = emitter.messages
        assertEquals(2, outbound.size)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_ACCEPTED, outbound[0].type)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_RESULT, outbound[1].type)

        val resultPayload = decodeProtocolPayload<WorkerCommandResultPayload>(
            outbound[1].payload!!,
            "WorkerCommandResultPayload",
        ).getOrElse { error("Expected result payload to decode: $it") }
        assertEquals(WorkerCommandResultStatuses.ERROR, resultPayload.status)

        val result = decodeProtocolPayload<BuiltInToolExecutionResult>(
            resultPayload.data,
            "BuiltInToolExecutionResult",
        ).getOrElse { error("Expected built-in result to decode: $it") }
        assertEquals(true, result.isError)
        assertEquals("invalid_signature", result.errorCode)
        assertEquals("Bad signature", result.errorMessage)
    }

    /**
     * Verifies that a payload-decoding failure (wrong command type) produces a `command.rejected`
     * with `INVALID_COMMAND_PAYLOAD` instead of a result.
     */
    @Test
    fun `invalid command type yields a command rejected envelope`() = runTest {
        val emitter = RecordingEmitter()
        val executor = RecordingExecutor(
            result = BuiltInToolExecutionResult(output = "unused")
        )
        val interaction = ToolCallInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-3",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = "int-3",
                payload = null,
            ),
            requestPayload = WorkerCommandRequestPayload(
                commandType = "wrong.command.type",
                data = buildJsonObject { put("any", "thing") },
            ),
            authorizationValidator = RejectingValidator("unused", "unused"),
            toolCallExecutor = executor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider(),
        )

        interaction.start()

        assertTrue(executor.executed.isEmpty(), "Executor should not run on invalid payload")
        val outbound = emitter.messages
        assertEquals(1, outbound.size)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_REJECTED, outbound[0].type)
    }

    /**
     * Verifies that when a prefixed tool name is sent through the protocol, the interaction
     * passes the full public name to the executor — prefix stripping is the executor's
     * responsibility (handled by `DefaultBuiltInToolCallExecutor`).
     */
    @Test
    fun `prefixed tool names are passed through to the executor`() = runTest {
        val emitter = RecordingEmitter()
        val input = buildJsonObject { put("path", "src/main.kt") }
        val authorization = buildAuthorization(
            toolName = "project1.read_text_file",
        )
        val signedRequest = SignedBuiltInToolExecutionRequest(
            signedRequest = signedRequestFor(authorization, input)
        )
        val requestPayload = signedRequest.toWorkerCommandRequestPayload()
            .getOrElse { error("Failed to build request payload for test: $it") }

        val executor = RecordingExecutor(
            result = BuiltInToolExecutionResult(output = "x")
        )
        val interaction = ToolCallInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-4",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = "int-4",
                payload = null,
            ),
            requestPayload = requestPayload,
            authorizationValidator = AuthorizingValidator(authorization),
            toolCallExecutor = executor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider(),
        )

        interaction.start()

        assertEquals("project1.read_text_file", executor.executed.single().toolName)
    }

    // --- helpers ---

    /**
     * Sanity check that the input parser correctly handles a JSON-encoded JsonObject string.
     * This protects the test helpers from breaking if the test JSON serializer changes.
     */
    @Test
    fun `input parser smoke test`() {
        val input = buildJsonObject { put("path", "notes.md") }
        val encoded = Json.encodeToString(JsonObject.serializer(), input)
        val parsed = Json.parseToJsonElement(encoded)
        val asObj = parsed as? JsonObject
        assertEquals("notes.md", asObj?.get("path")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    /**
     * Smoke test that round-trips a BuiltInToolExecutionAuthorization through the serializer
     * and reproduces the parsing logic used by [ToolCallInteraction.parseInputObject].
     */
    @Test
    fun `authorization round-trip preserves input json`() {
        val input = buildJsonObject { put("path", "notes.md") }
        val encodedInput = Json.encodeToString(JsonObject.serializer(), input)
        val authorization = BuiltInToolExecutionAuthorization(
            toolCallId = 900L,
            sessionId = 1L,
            messageId = 2L,
            toolDefinitionId = 3L,
            toolName = "read_text_file",
            workerId = 4L,
            builtInToolName = "read_text_file",
            input = encodedInput,
            approved = true,
            denialReason = null,
        )
        val payload = Json.encodeToString(BuiltInToolExecutionAuthorization.serializer(), authorization)
        val decoded = Json.decodeFromString(BuiltInToolExecutionAuthorization.serializer(), payload)
        // Replicate the interaction's parseInputObject logic.
        val parsed = Json.parseToJsonElement(decoded.input!!) as? JsonObject
        assertEquals("notes.md", parsed?.get("path")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    /**
     * Builds an [BuiltInToolExecutionAuthorization] fixture with sensible defaults.
     */
    private fun buildAuthorization(
        toolCallId: Long = 900L,
        toolName: String,
    ): BuiltInToolExecutionAuthorization = BuiltInToolExecutionAuthorization(
        toolCallId = toolCallId,
        sessionId = 1L,
        messageId = 2L,
        toolDefinitionId = 3L,
        toolName = toolName,
        workerId = 4L,
        builtInToolName = toolName.substringAfterLast('.'),
        input = null,
        approved = true,
        denialReason = null,
    )

    /**
     * Builds a [SignedRequest] whose payload is the [authorization] JSON with the [input]
     * object embedded in the `input` field. The interaction decodes the input from that field.
     */
    private fun signedRequestFor(
        authorization: BuiltInToolExecutionAuthorization,
        input: JsonObject,
    ): SignedRequest {
        val authWithInput = authorization.copy(
            input = Json.encodeToString(JsonObject.serializer(), input)
        )
        return SignedRequest(
            payload = Json.encodeToString(BuiltInToolExecutionAuthorization.serializer(), authWithInput),
            signature = "signature-base64",
            signerId = "device-1",
            timestamp = 1_700_000_000_000,
            nonce = "nonce-1",
        )
    }

    // --- test doubles ---

    /**
     * Validator that always returns [BuiltInToolAuthorizationValidationResult.Authorized] with
     * the supplied authorization.
     */
    private class AuthorizingValidator(
        private val authorization: BuiltInToolExecutionAuthorization,
    ) : BuiltInToolAuthorizationValidator {
        override suspend fun validate(signedRequest: SignedRequest): BuiltInToolAuthorizationValidationResult {
            return BuiltInToolAuthorizationValidationResult.Authorized(authorization)
        }
    }

    /**
     * Validator that always returns a rejection with the given [code]/[message].
     */
    private class RejectingValidator(
        private val code: String,
        private val message: String,
    ) : BuiltInToolAuthorizationValidator {
        override suspend fun validate(signedRequest: SignedRequest): BuiltInToolAuthorizationValidationResult {
            return BuiltInToolAuthorizationValidationResult.InvalidSignature(
                code = code,
                message = message,
            )
        }
    }

    /**
     * Executor that records the requests it receives and returns the configured result.
     */
    private class RecordingExecutor(
        private val result: BuiltInToolExecutionResult,
    ) : BuiltInToolCallExecutor {
        val executed: MutableList<BuiltInToolExecutionRequest> = mutableListOf()

        override suspend fun execute(request: BuiltInToolExecutionRequest): BuiltInToolExecutionResult {
            executed += request
            return result
        }
    }

    /**
     * Recording outbound emitter used for assertions.
     */
    private class RecordingEmitter : OutboundMessageEmitter {
        val messages: MutableList<WorkerProtocolMessage> = mutableListOf()
        override suspend fun emit(message: WorkerProtocolMessage) {
            messages += message
        }
    }

    /**
     * Deterministic message-id provider for stable protocol assertions.
     */
    private class SequenceMessageIdProvider : MessageIdProvider {
        private var counter: Int = 0
        override fun nextMessageId(): String {
            counter += 1
            return "msg-$counter"
        }
    }
}
