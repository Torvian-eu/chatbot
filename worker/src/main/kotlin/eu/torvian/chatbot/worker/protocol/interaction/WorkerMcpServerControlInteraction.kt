package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.Either
import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandAccepted
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandResult
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpServerControlProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerRefreshToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerTestConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.worker.mcp.WorkerMcpServerControlCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Active interaction that runs one MCP server-control command lifecycle.
 *
 * Supported command types:
 * - `mcp.server.start`
 * - `mcp.server.stop`
 * - `mcp.server.test_connection`
 * - `mcp.server.refresh_tools`
 *
 * @property envelope Original inbound `command.request` envelope.
 * @property requestPayload Decoded command-request payload for this interaction.
 * @property executor Command-specific execution adapter used by this interaction.
 * @property messageIdProvider Message-ID provider used for outbound envelopes.
 */
class WorkerMcpServerControlInteraction(
    private val envelope: WorkerProtocolMessage,
    private val requestPayload: WorkerCommandRequestPayload,
    private val executor: WorkerMcpServerControlCommandExecutor,
    emitter: WorkerOutboundMessageEmitter,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : AbstractChannelBackedWorkerInteraction(
    interactionId = envelope.interactionId,
    emitter = emitter
) {
    /**
     * Executes a one-shot lifecycle for supported MCP server-control command types.
     */
    override suspend fun start() {
        when (requestPayload.commandType) {
            WorkerProtocolCommandTypes.MCP_SERVER_START -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerStartCommandData() },
                execute = { command -> executor.startServer(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.start payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_STOP -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerStopCommandData() },
                execute = { command -> executor.stopServer(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.stop payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerTestConnectionCommandData() },
                execute = { command -> executor.testConnection(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.test_connection payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_REFRESH_TOOLS -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerRefreshToolsCommandData() },
                execute = { command -> executor.refreshTools(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.refresh_tools payload"
            )

            else -> {
                emitter.emit(
                    rejectedMessage(
                        reasonCode = WorkerProtocolRejectionReasons.UNSUPPORTED_COMMAND_TYPE,
                        message = "Unsupported command type '${requestPayload.commandType}'"
                    )
                )
            }
        }
    }

    /**
     * Runs the shared one-shot lifecycle for typed request/result MCP server-control commands.
     *
     * @param decodeRequest Function that decodes typed request command data.
     * @param execute Function that executes command logic and returns typed success/error result data.
     * @param decodeFailureMessage Rejection message used when request decoding fails.
     */
    private suspend fun <TRequest, TSuccess> runOneShotCommand(
        decodeRequest: WorkerCommandRequestPayload.() -> Either<WorkerMcpServerControlProtocolMappingError, TRequest>,
        execute: suspend (TRequest) -> Either<WorkerMcpServerControlErrorResultData, TSuccess>,
        mapSuccessResult: (TSuccess) -> Either<WorkerMcpServerControlProtocolMappingError, WorkerCommandResultPayload>,
        decodeFailureMessage: String
    ) {
        val commandData = requestPayload.decodeRequest().getOrElse { mappingError ->
            emitter.emit(
                rejectedMessage(
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_COMMAND_PAYLOAD,
                    message = decodeFailureMessage,
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

        val resultPayload = execute(commandData).fold(
            ifLeft = { errorResult -> errorResult.toWorkerCommandResultPayload() },
            ifRight = mapSuccessResult
        ).getOrElse { mappingError ->
            error("Unexpected failure encoding ${requestPayload.commandType} result payload: $mappingError")
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
     * Builds a `command.rejected` envelope for MCP server-control command failures.
     *
     * @param reasonCode Stable machine-readable rejection reason.
     * @param message Human-readable rejection description.
     * @param details Optional structured diagnostics.
     * @return Rejection envelope ready to send.
     */
    private fun rejectedMessage(
        reasonCode: String,
        message: String,
        details: JsonObject? = null
    ): WorkerProtocolMessage {
        return commandRejected(
            id = messageIdProvider.nextMessageId(),
            replyTo = envelope.id,
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
     * Converts an MCP server-control mapping failure to structured rejection diagnostics.
     *
     * @param mappingError Mapping failure returned by shared protocol mapping helpers.
     * @return Structured diagnostics suitable for `command.rejected` payload details.
     */
    private fun mappingErrorDetails(mappingError: WorkerMcpServerControlProtocolMappingError): JsonObject =
        buildJsonObject {
            when (mappingError) {
                is WorkerMcpServerControlProtocolMappingError.InvalidCommandType -> {
                    put("error", "invalid_command_type")
                    put("expected", mappingError.expected)
                    put("actual", mappingError.actual)
                }

                is WorkerMcpServerControlProtocolMappingError.SerializationFailed -> {
                    put("error", "serialization_failed")
                    put("operation", mappingError.operation)
                    put("targetType", mappingError.targetType)
                    mappingError.details?.let { put("details", it) }
                }
            }
        }
}

