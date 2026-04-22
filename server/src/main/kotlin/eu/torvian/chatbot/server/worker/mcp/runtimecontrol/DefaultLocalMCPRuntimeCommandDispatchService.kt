package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.*
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.*
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService

/**
 * Default MCP runtime command adapter that dispatches typed commands through [WorkerCommandDispatchService].
 *
 * @property workerCommandDispatchService Generic worker dispatcher used for command lifecycle orchestration.
 */
class DefaultLocalMCPRuntimeCommandDispatchService(
    private val workerCommandDispatchService: WorkerCommandDispatchService
) : LocalMCPRuntimeCommandDispatchService {
    override suspend fun startServer(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerStartResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
            requestPayload = WorkerMcpServerStartCommandData(serverId).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerStartResultData(
                    completedCommandType
                )
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerStartErrorResultData(
                    completedCommandType
                )
            }
        )

    override suspend fun stopServer(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerStopResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
            requestPayload = WorkerMcpServerStopCommandData(serverId).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerStopResultData(
                    completedCommandType
                )
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerStopErrorResultData(
                    completedCommandType
                )
            }
        )

    override suspend fun testConnection(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerTestConnectionResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
            requestPayload = WorkerMcpServerTestConnectionCommandData(serverId).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerTestConnectionResultData(completedCommandType)
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerTestConnectionErrorResultData(completedCommandType)
            }
        )

    override suspend fun discoverTools(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerDiscoverToolsResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
            requestPayload = WorkerMcpServerDiscoverToolsCommandData(serverId).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerDiscoverToolsResultData(
                    completedCommandType
                )
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerDiscoverToolsErrorResultData(completedCommandType)
            }
        )

    override suspend fun getRuntimeStatus(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerGetRuntimeStatusResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS,
            requestPayload = WorkerMcpServerGetRuntimeStatusCommandData(serverId).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerGetRuntimeStatusResultData(completedCommandType)
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerGetRuntimeStatusErrorResultData(completedCommandType)
            }
        )

    override suspend fun listRuntimeStatuses(
        workerId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerListRuntimeStatusesResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
            requestPayload = WorkerMcpServerListRuntimeStatusesCommandData.toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerListRuntimeStatusesResultData(completedCommandType)
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerListRuntimeStatusesErrorResultData(completedCommandType)
            }
        )

    override suspend fun createServer(
        workerId: Long,
        server: LocalMCPServerDto
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerCreateResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_CREATE,
            requestPayload = WorkerMcpServerCreateCommandData(server = server).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerCreateResultData(completedCommandType)
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerCreateErrorResultData(completedCommandType)
            }
        )

    override suspend fun updateServer(
        workerId: Long,
        server: LocalMCPServerDto
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerUpdateResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_UPDATE,
            requestPayload = WorkerMcpServerUpdateCommandData(server = server).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerUpdateResultData(completedCommandType)
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerUpdateErrorResultData(completedCommandType)
            }
        )

    override suspend fun deleteServer(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerDeleteResultData> =
        dispatchAndDecode(
            workerId = workerId,
            commandType = WorkerProtocolCommandTypes.MCP_SERVER_DELETE,
            requestPayload = WorkerMcpServerDeleteCommandData(serverId = serverId).toWorkerCommandRequestPayload(),
            decodeSuccessResult = { result, completedCommandType ->
                result.toWorkerMcpServerDeleteResultData(completedCommandType)
            },
            decodeErrorResult = { result, completedCommandType ->
                result.toWorkerMcpServerDeleteErrorResultData(completedCommandType)
            }
        )

    /**
     * Dispatches a typed request payload and decodes completed lifecycle payloads to typed success/error data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param commandType Expected worker protocol command type.
     * @param requestPayload Pre-encoded request payload mapping result.
     * @param decodeSuccessResult Function that decodes a `success` status [eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchSuccess.result].
     * @param decodeErrorResult Function that decodes an `error` status [eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchSuccess.result].
     * @return Either typed orchestration error or decoded typed success payload.
     */
    private suspend fun <TSuccess> dispatchAndDecode(
        workerId: Long,
        commandType: String,
        requestPayload: Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload>,
        decodeSuccessResult: (WorkerCommandResultPayload, String) -> Either<WorkerMcpRuntimeCommandProtocolMappingError, TSuccess>,
        decodeErrorResult: (WorkerCommandResultPayload, String) -> Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData>
    ): Either<LocalMCPRuntimeCommandDispatchError, TSuccess> = either {
        val payload = requestPayload.mapLeft { mappingError ->
            mappingError.toServerError(commandType = commandType)
        }.bind()

        val dispatchResult = workerCommandDispatchService.dispatch(
            workerId = workerId,
            commandRequestPayload = payload
        ).mapLeft { dispatchError ->
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(dispatchError)
        }.bind()

        when (dispatchResult.result.status) {
            WorkerCommandResultStatuses.SUCCESS -> {
                decodeSuccessResult(dispatchResult.result, dispatchResult.commandType)
                    .mapLeft { mappingError ->
                        mappingError.toServerError(commandType = dispatchResult.commandType)
                    }
                    .bind()
            }

            WorkerCommandResultStatuses.ERROR -> {
                val errorResult = decodeErrorResult(dispatchResult.result, dispatchResult.commandType)
                    .mapLeft { mappingError ->
                        mappingError.toServerError(commandType = dispatchResult.commandType)
                    }
                    .bind()

                raise(errorResult.toDispatchError(commandType = dispatchResult.commandType))
            }

            else -> {
                raise(
                    LocalMCPRuntimeCommandDispatchError.UnexpectedResultStatus(
                        commandType = dispatchResult.commandType,
                        status = dispatchResult.result.status
                    )
                )
            }
        }
    }

    /**
     * Maps decoded worker MCP server-control error payloads into dispatch-layer runtime command failures.
     *
     * @receiver Decoded worker error payload.
     * @param commandType Worker protocol command type tied to the failed command.
     * @return Orchestration error describing the worker command failure.
     */
    private fun WorkerMcpServerControlErrorResultData.toDispatchError(
        commandType: String
    ): LocalMCPRuntimeCommandDispatchError =
        LocalMCPRuntimeCommandDispatchError.CommandFailed(
            commandType = commandType,
            code = code,
            message = message,
            details = details
        )

    /**
     * Maps protocol payload mapping failures into server-side orchestration errors.
     *
     * @receiver Mapping error returned by protocol encode/decode helpers.
     * @param commandType Command type being orchestrated.
     * @return Server-side orchestration error.
     */
    private fun WorkerMcpRuntimeCommandProtocolMappingError.toServerError(
        commandType: String
    ): LocalMCPRuntimeCommandDispatchError = when (this) {
        is WorkerMcpRuntimeCommandProtocolMappingError.InvalidCommandType -> {
            LocalMCPRuntimeCommandDispatchError.InvalidPayload(
                commandType = commandType,
                details = "Expected commandType=$expected but was $actual"
            )
        }

        is WorkerMcpRuntimeCommandProtocolMappingError.SerializationFailed -> {
            LocalMCPRuntimeCommandDispatchError.InvalidPayload(
                commandType = commandType,
                details = "Serialization failed during $operation for $targetType${details?.let { ": $it" } ?: ""}"
            )
        }
    }
}