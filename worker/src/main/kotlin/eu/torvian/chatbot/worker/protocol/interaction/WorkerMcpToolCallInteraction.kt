package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpToolCallProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandAccepted
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandResult
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toLocalMcpToolCallRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.worker.mcp.WorkerToolCallExecutor
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Active interaction that runs one `mcp.tool.call` command lifecycle.
 *
 * @property envelope Original inbound `command.request` envelope.
 * @property requestPayload Decoded command-request payload for this interaction.
 * @property toolCallExecutor Executor used to perform local MCP tool calls.
 * @property emitter Outbound protocol emitter used for lifecycle responses.
 * @property messageIdProvider Message-ID provider used for outbound envelopes.
 */
class WorkerMcpToolCallInteraction(
    private val envelope: WorkerProtocolMessage,
    private val requestPayload: WorkerCommandRequestPayload,
    private val toolCallExecutor: WorkerToolCallExecutor,
    emitter: WorkerOutboundMessageEmitter,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : AbstractChannelBackedWorkerInteraction(
    interactionId = envelope.interactionId,
    emitter = emitter
) {
    /**
     * Executes the command lifecycle and emits protocol responses.
     *
     * The interaction emits `command.accepted` immediately after payload validation and then emits
     * `command.result` after tool execution completes.
     */
    override suspend fun start() {
        val localRequest = requestPayload.toLocalMcpToolCallRequest().getOrElse { mappingError ->
            emitter.emit(
                rejectedMessage(
                    replyTo = envelope.id,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_COMMAND_PAYLOAD,
                    message = "Unable to decode mcp.tool.call payload",
                    details = mappingErrorDetails(mappingError)
                )
            )
            return
        }

        emitter.emit(
            commandAccepted(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = WorkerCommandAcceptedPayload
            )
        )

        // Extension point for future interactive flows, for example awaiting explicit proceed.
        // val proceed = awaitNextMessage()

        val executionResult = toolCallExecutor.execute(localRequest)
        val resultPayload = executionResult.toWorkerCommandResultPayload()
            .getOrElse { mappingError ->
                error("Unexpected failure encoding mcp.tool.call result payload: $mappingError")
            }

        emitter.emit(
            commandResult(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = resultPayload
            )
        )
    }

    /**
     * Builds a `command.rejected` envelope for MCP payload failures.
     *
     * @param replyTo Request envelope identifier that the rejection acknowledges.
     * @param reasonCode Stable machine-readable rejection reason.
     * @param message Human-readable rejection description.
     * @param details Optional structured diagnostics.
     * @return Rejection envelope ready to send.
     */
    private fun rejectedMessage(
        replyTo: String,
        reasonCode: String,
        message: String,
        details: JsonObject? = null
    ): WorkerProtocolMessage {
        return commandRejected(
            id = messageIdProvider.nextMessageId(),
            replyTo = replyTo,
            interactionId = interactionId,
            payload = WorkerCommandRejectedPayload(
                commandType = requestPayload.commandType,
                reasonCode = reasonCode,
                message = message,
                details = details
            )
        )
    }

    /**
     * Converts an MCP payload-mapping failure into structured rejection diagnostics.
     *
     * @param mappingError Failure returned by the shared MCP mapping helpers.
     * @return Structured diagnostics suitable for a rejection payload.
     */
    private fun mappingErrorDetails(mappingError: WorkerMcpToolCallProtocolMappingError): JsonObject =
        buildJsonObject {
            when (mappingError) {

                is WorkerMcpToolCallProtocolMappingError.InvalidCommandType -> {
                    put("error", "invalid_command_type")
                    put("expected", mappingError.expected)
                    put("actual", mappingError.actual)
                }

                is WorkerMcpToolCallProtocolMappingError.SerializationFailed -> {
                    put("error", "serialization_failed")
                    put("operation", mappingError.operation)
                    put("targetType", mappingError.targetType)
                    mappingError.details?.let { put("details", it) }
                }
            }
        }
}
