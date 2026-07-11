package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandAccepted
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandResult
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.BuiltInToolProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toSignedBuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.worker.builtin.BuiltInToolAuthorizationValidationResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolAuthorizationValidator
import eu.torvian.chatbot.worker.builtin.BuiltInToolCallExecutor
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Active interaction that runs one direct (non-MCP) `tool.call` command lifecycle.
 *
 * Decodes the signed request, verifies the detached signature, dispatches the built-in tool, and
 * emits the protocol responses. Authorization failures become a `command.result` with
 * `isError = true`; mapping failures produce a `command.rejected`.
 *
 * @property envelope Original inbound `command.request` envelope.
 * @property requestPayload Decoded command-request payload for this interaction.
 * @property authorizationValidator Verifier that validates the app-signed authorization payload.
 * @property toolCallExecutor Executor used to perform the actual built-in tool call.
 * @property emitter Outbound protocol emitter used for lifecycle responses.
 * @property messageIdProvider Message-ID provider used for outbound envelopes.
 */
class ToolCallInteraction(
    private val envelope: WorkerProtocolMessage,
    private val requestPayload: WorkerCommandRequestPayload,
    private val authorizationValidator: BuiltInToolAuthorizationValidator,
    private val toolCallExecutor: BuiltInToolCallExecutor,
    emitter: OutboundMessageEmitter,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider(),
) : ChannelBackedInteraction(
    interactionId = envelope.interactionId,
    emitter = emitter
) {
    companion object {
        private val inputParser = Json { ignoreUnknownKeys = true }
    }

    /**
     * Executes the command lifecycle: decode the signed request, verify the signature, dispatch
     * the built-in tool, and emit protocol responses.
     */
    override suspend fun start() {
        val signedRequest = requestPayload.toSignedBuiltInToolExecutionRequest().getOrElse { mappingError ->
            emitter.emit(
                rejectedMessage(
                    replyTo = envelope.id,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_COMMAND_PAYLOAD,
                    message = "Unable to decode tool.call signed request payload",
                    details = mappingErrorDetails(mappingError),
                ),
            )
            return
        }

        val validationResult = authorizationValidator.validate(signedRequest.signedRequest)

        if (validationResult is BuiltInToolAuthorizationValidationResult.Rejected) {
            val result = BuiltInToolExecutionResult(
                output = null,
                isError = true,
                errorMessage = validationResult.message,
                errorCode = validationResult.code,
            )
            emitAccepted()
            emitResult(result)
            return
        }

        val authorization = (validationResult as BuiltInToolAuthorizationValidationResult.Authorized).authorization
        val innerInput = parseInputObject(authorization.input)

        emitAccepted()
        val executionResult = toolCallExecutor.execute(
            toolName = authorization.builtInToolName,
            input = innerInput,
        )
        emitResult(executionResult)
    }

    private suspend fun emitAccepted() {
        emitter.emit(
            commandAccepted(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = WorkerCommandAcceptedPayload,
            ),
        )
    }

    private suspend fun emitResult(result: BuiltInToolExecutionResult) {
        val resultPayload = result.toWorkerCommandResultPayload().getOrElse { mappingError ->
            error("Unexpected failure encoding tool.call result payload: $mappingError")
        }
        emitter.emit(
            commandResult(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = resultPayload,
            ),
        )
    }

    private fun rejectedMessage(
        replyTo: String,
        reasonCode: String,
        message: String,
        details: JsonObject? = null,
    ): WorkerProtocolMessage {
        return commandRejected(
            id = messageIdProvider.nextMessageId(),
            replyTo = replyTo,
            interactionId = interactionId,
            payload = WorkerCommandRejectedPayload(
                commandType = requestPayload.commandType,
                reasonCode = reasonCode,
                message = message,
                details = details,
            ),
        )
    }

    private fun mappingErrorDetails(mappingError: BuiltInToolProtocolMappingError): JsonObject =
        buildJsonObject {
            when (mappingError) {
                is BuiltInToolProtocolMappingError.InvalidCommandType -> {
                    put("error", "invalid_command_type")
                    put("expected", mappingError.expected)
                    put("actual", mappingError.actual)
                }
                is BuiltInToolProtocolMappingError.SerializationFailed -> {
                    put("error", "serialization_failed")
                    put("operation", mappingError.operation)
                    put("targetType", mappingError.targetType)
                    mappingError.details?.let { put("details", it) }
                }
            }
        }

    private fun parseInputObject(input: String?): JsonObject {
        if (input.isNullOrBlank()) return JsonObject(emptyMap())
        return try {
            inputParser.parseToJsonElement(input) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }
}
