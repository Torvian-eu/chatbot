package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.Either
import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandAccepted
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandResult
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpRuntimeCommandProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerDiscoverToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerGetRuntimeStatusCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerListRuntimeStatusesCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerTestConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerTestDraftConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.worker.mcp.McpRuntimeCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Active interaction that runs one worker local MCP runtime command lifecycle.
 *
 * Supported command types:
 * - `mcp.server.start`
 * - `mcp.server.stop`
 * - `mcp.server.test_connection`
 * - `mcp.server.discover_tools`
 * - `mcp.server.get_runtime_status`
 * - `mcp.server.list_runtime_statuses`
 * - `mcp.server.create`
 * - `mcp.server.update`
 * - `mcp.server.delete`
 *
 * @property envelope Original inbound `command.request` envelope.
 * @property requestPayload Decoded command-request payload for this interaction.
 * @property executor Command-specific execution adapter used by this interaction.
 * @property messageIdProvider Message-ID provider used for outbound envelopes.
 */
class McpRuntimeCommandInteraction(
    private val envelope: WorkerProtocolMessage,
    private val requestPayload: WorkerCommandRequestPayload,
    private val executor: McpRuntimeCommandExecutor,
    emitter: OutboundMessageEmitter,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) : ChannelBackedInteraction(
    interactionId = envelope.interactionId,
    emitter = emitter
) {
    /**
     * Executes a one-shot lifecycle for supported worker local MCP runtime command types.
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

            WorkerProtocolCommandTypes.MCP_SERVER_TEST_DRAFT_CONNECTION -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerTestDraftConnectionCommandData() },
                execute = { command -> executor.testDraftConnection(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.test_draft_connection payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerDiscoverToolsCommandData() },
                execute = { command -> executor.discoverTools(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.discover_tools payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerGetRuntimeStatusCommandData() },
                execute = { command -> executor.getRuntimeStatus(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.get_runtime_status payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerListRuntimeStatusesCommandData() },
                execute = { command -> executor.listRuntimeStatuses(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.list_runtime_statuses payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_CREATE -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerCreateCommandData() },
                execute = { command -> executor.createServer(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.create payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_UPDATE -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerUpdateCommandData() },
                execute = { command -> executor.updateServer(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.update payload"
            )

            WorkerProtocolCommandTypes.MCP_SERVER_DELETE -> runOneShotCommand(
                decodeRequest = { toWorkerMcpServerDeleteCommandData() },
                execute = { command -> executor.deleteServer(command) },
                mapSuccessResult = { result -> result.toWorkerCommandResultPayload() },
                decodeFailureMessage = "Unable to decode mcp.server.delete payload"
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
     * Runs the shared one-shot lifecycle for typed request/result worker local MCP runtime commands.
     *
     * @param decodeRequest Function that decodes typed request command data.
     * @param execute Function that executes command logic and returns typed success/error result data.
     * @param decodeFailureMessage Rejection message used when request decoding fails.
     */
    private suspend fun <TRequest, TSuccess> runOneShotCommand(
        decodeRequest: WorkerCommandRequestPayload.() -> Either<WorkerMcpRuntimeCommandProtocolMappingError, TRequest>,
        execute: suspend (TRequest) -> Either<WorkerMcpServerControlErrorResultData, TSuccess>,
        mapSuccessResult: (TSuccess) -> Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload>,
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
     * Builds a `command.rejected` envelope for worker local MCP runtime command failures.
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
     * Converts a worker local MCP runtime command mapping failure to structured rejection diagnostics.
     *
     * @param mappingError Mapping failure returned by shared protocol mapping helpers.
     * @return Structured diagnostics suitable for `command.rejected` payload details.
     */
    private fun mappingErrorDetails(mappingError: WorkerMcpRuntimeCommandProtocolMappingError): JsonObject =
        buildJsonObject {
            when (mappingError) {
                is WorkerMcpRuntimeCommandProtocolMappingError.InvalidCommandType -> {
                    put("error", "invalid_command_type")
                    put("expected", mappingError.expected)
                    put("actual", mappingError.actual)
                }

                is WorkerMcpRuntimeCommandProtocolMappingError.SerializationFailed -> {
                    put("error", "serialization_failed")
                    put("operation", mappingError.operation)
                    put("targetType", mappingError.targetType)
                    mappingError.details?.let { put("details", it) }
                }
            }
        }
}
