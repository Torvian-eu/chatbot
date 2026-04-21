package eu.torvian.chatbot.server.worker.mcp.command.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerGetRuntimeStatusCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerListRuntimeStatusesCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchSuccess
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.DefaultLocalMCPRuntimeCommandDispatchService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

/**
 * Verifies typed worker dispatch orchestration for MCP runtime-control commands.
 */
class DefaultLocalMCPRuntimeCommandDispatchServiceTest {
    /**
     * Mocked generic worker dispatch service.
     */
    private val workerCommandDispatchService: WorkerCommandDispatchService = mockk()

    /**
     * Subject under test.
     */
    private val service = DefaultLocalMCPRuntimeCommandDispatchService(workerCommandDispatchService)

    init {
        every { workerCommandDispatchService.defaultTimeout } returns 30.seconds
    }

    /**
     * Verifies that start-server dispatch builds the correct command-request payload.
     */
    @Test
    fun `start server builds start command request payload`() = runTest {
        val payloadSlot = slot<WorkerCommandRequestPayload>()
        coEvery { workerCommandDispatchService.dispatch(10L, capture(payloadSlot), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 10L,
                interactionId = "i-1",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                result = WorkerMcpServerStartResultData(serverId = 7L)
                    .toWorkerCommandResultPayload()
                    .orError()
            ).right()

        val result = service.startServer(workerId = 10L, serverId = 7L).requireRight()

        assertEquals(7L, result.serverId)
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_START, payloadSlot.captured.commandType)
        assertEquals(7L, payloadSlot.captured.toWorkerMcpServerStartCommandData().orError().serverId)
    }

    /**
     * Verifies that stop-server dispatch builds the correct command-request payload.
     */
    @Test
    fun `stop server builds stop command request payload`() = runTest {
        val payloadSlot = slot<WorkerCommandRequestPayload>()
        coEvery { workerCommandDispatchService.dispatch(10L, capture(payloadSlot), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 10L,
                interactionId = "i-2",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
                result = WorkerMcpServerStopResultData(serverId = 8L)
                    .toWorkerCommandResultPayload()
                    .orError()
            ).right()

        val result = service.stopServer(workerId = 10L, serverId = 8L).requireRight()

        assertEquals(8L, result.serverId)
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_STOP, payloadSlot.captured.commandType)
        assertEquals(8L, payloadSlot.captured.toWorkerMcpServerStopCommandData().orError().serverId)
    }

    /**
     * Verifies that test-connection command results are decoded to typed data.
     */
    @Test
    fun `test connection decodes typed result payload`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 11L,
                interactionId = "i-3",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
                result = WorkerMcpServerTestConnectionResultData(
                    serverId = 9L,
                    success = true,
                    discoveredToolCount = 4,
                    message = "ok"
                ).toWorkerCommandResultPayload().orError()
            ).right()

        val result = service.testConnection(workerId = 11L, serverId = 9L).requireRight()

        assertEquals(9L, result.serverId)
        assertEquals(true, result.success)
        assertEquals(4, result.discoveredToolCount)
        assertEquals("ok", result.message)
    }

    /**
     * Verifies that discover-tools command results are decoded to typed data.
     */
    @Test
    fun `discover tools decodes typed result payload`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 12L,
                interactionId = "i-4",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
                result = WorkerMcpServerDiscoverToolsResultData(
                    serverId = 10L,
                    tools = emptyList()
                ).toWorkerCommandResultPayload().orError()
            ).right()

        val result = service.discoverTools(workerId = 12L, serverId = 10L).requireRight()

        assertEquals(10L, result.serverId)
        assertEquals(0, result.tools.size)
    }

    /**
     * Verifies rejection outcomes are mapped to typed orchestration errors.
     */
    @Test
    fun `rejected dispatch maps to rejected error`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchError.Rejected(
                workerId = 13L,
                interactionId = "i-5",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                rejection = WorkerCommandRejectedPayload(
                    commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                    reasonCode = "NOT_SUPPORTED",
                    message = "nope"
                )
            ).left()

        val error = service.startServer(workerId = 13L, serverId = 11L).requireLeft()

        val rejected = assertIs<LocalMCPRuntimeCommandDispatchError.DispatchFailed>(error)
        val dispatchError = assertIs<WorkerCommandDispatchError.Rejected>(rejected.error)
        assertEquals("NOT_SUPPORTED", dispatchError.rejection.reasonCode)
        assertEquals("nope", dispatchError.rejection.message)
    }

    /**
     * Verifies timeout outcomes are mapped to typed orchestration errors.
     */
    @Test
    fun `timeout dispatch maps to timeout error`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchError.TimedOut(
                workerId = 14L,
                interactionId = "i-6",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
                timeout = 5.seconds
            ).left()

        val error = service.stopServer(workerId = 14L, serverId = 12L).requireLeft()

        assertEquals(
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.TimedOut(
                    workerId = 14L,
                    interactionId = "i-6",
                    commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
                    timeout = 5.seconds
                )
            ),
            error
        )
    }

    /**
     * Verifies disconnect outcomes are mapped to typed orchestration errors.
     */
    @Test
    fun `session disconnected maps to disconnected error`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchError.SessionDisconnected(
                workerId = 15L,
                interactionId = "i-7",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
                reason = "socket closed"
            ).left()

        val error = service.testConnection(workerId = 15L, serverId = 13L).requireLeft()

        assertEquals(
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.SessionDisconnected(
                    workerId = 15L,
                    interactionId = "i-7",
                    commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
                    reason = "socket closed"
                )
            ),
            error
        )
    }

    /**
     * Verifies malformed lifecycle payload outcomes are mapped to typed orchestration errors.
     */
    @Test
    fun `malformed lifecycle payload maps to malformed error`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchError.MalformedLifecyclePayload(
                workerId = 16L,
                interactionId = "i-8",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
                messageType = "command.result",
                reason = "invalid json"
            ).left()

        val error = service.discoverTools(workerId = 16L, serverId = 14L).requireLeft()

        assertEquals(
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.MalformedLifecyclePayload(
                    workerId = 16L,
                    interactionId = "i-8",
                    commandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
                    messageType = "command.result",
                    reason = "invalid json"
                )
            ),
            error
        )
    }

    /**
     * Verifies malformed completed result data is mapped to a typed invalid payload error.
     */
    @Test
    fun `malformed completed payload maps to invalid result payload error`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 17L,
                interactionId = "i-9",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                result = WorkerCommandResultPayload(
                    status = "success",
                    data = buildJsonObject { put("unexpected", "shape") }
                )
            ).right()

        val error = service.startServer(workerId = 17L, serverId = 15L).requireLeft()

        assertIs<LocalMCPRuntimeCommandDispatchError.InvalidPayload>(error)
    }

    /**
     * Verifies error-status completed payloads decode to a typed command-failed orchestration error.
     */
    @Test
    fun `error status completed payload maps to command failed error`() = runTest {
        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 18L,
                interactionId = "i-10",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                result = WorkerMcpServerControlErrorResultData(
                    serverId = 16L,
                    code = "START_FAILED",
                    message = "Runtime start failed",
                    details = "Executable not found"
                ).toWorkerCommandResultPayload(status = WorkerCommandResultStatuses.ERROR).orError()
            ).right()

        val error = service.startServer(workerId = 18L, serverId = 16L).requireLeft()

        assertEquals(
            LocalMCPRuntimeCommandDispatchError.CommandFailed(
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
                code = "START_FAILED",
                message = "Runtime start failed",
                details = "Executable not found"
            ),
            error
        )
    }

    /**
     * Verifies get-runtime-status dispatch builds the correct command-request payload and decodes result.
     */
    @Test
    fun `get runtime status builds request and decodes typed result`() = runTest {
        val now = Clock.System.now()
        val payloadSlot = slot<WorkerCommandRequestPayload>()
        val runtimeStatus = LocalMcpServerRuntimeStatusDto(
            serverId = 17L,
            state = LocalMcpServerRuntimeStateDto.RUNNING,
            connectedAt = now,
            lastActivityAt = now
        )
        coEvery { workerCommandDispatchService.dispatch(20L, capture(payloadSlot), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 20L,
                interactionId = "i-11",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS,
                result = WorkerMcpServerGetRuntimeStatusResultData(status = runtimeStatus)
                    .toWorkerCommandResultPayload()
                    .orError()
            ).right()

        val result = service.getRuntimeStatus(workerId = 20L, serverId = 17L).requireRight()

        assertEquals(runtimeStatus, result.status)
        assertEquals(WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS, payloadSlot.captured.commandType)
        assertEquals(17L, payloadSlot.captured.toWorkerMcpServerGetRuntimeStatusCommandData().orError().serverId)
    }

    /**
     * Verifies list-runtime-statuses dispatch builds the correct command-request payload and decodes result.
     */
    @Test
    fun `list runtime statuses builds request and decodes typed result`() = runTest {
        val runtimeStatus = LocalMcpServerRuntimeStatusDto(
            serverId = 18L,
            state = LocalMcpServerRuntimeStateDto.STOPPED,
            errorMessage = "worker disconnected"
        )
        val payloadSlot = slot<WorkerCommandRequestPayload>()
        coEvery { workerCommandDispatchService.dispatch(21L, capture(payloadSlot), any()) } returns
            WorkerCommandDispatchSuccess(
                workerId = 21L,
                interactionId = "i-12",
                commandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
                result = WorkerMcpServerListRuntimeStatusesResultData(statuses = listOf(runtimeStatus))
                    .toWorkerCommandResultPayload()
                    .orError()
            ).right()

        val result = service.listRuntimeStatuses(workerId = 21L).requireRight()

        assertEquals(1, result.statuses.size)
        assertEquals(runtimeStatus, result.statuses.single())
        assertEquals(
            WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
            payloadSlot.captured.commandType
        )
        payloadSlot.captured.toWorkerMcpServerListRuntimeStatusesCommandData().orError()
    }

    /**
     * Returns the right value from an [Either] or fails the test.
     *
     * @receiver Either value under assertion.
     * @return Right value.
     */
    private fun <T> Either<LocalMCPRuntimeCommandDispatchError, T>.requireRight(): T = fold(
        ifLeft = { error("Expected Right but was Left: $it") },
        ifRight = { it }
    )

    /**
     * Returns the left value from an [Either] or fails the test.
     *
     * @receiver Either value under assertion.
     * @return Left value.
     */
    private fun <T> Either<LocalMCPRuntimeCommandDispatchError, T>.requireLeft(): LocalMCPRuntimeCommandDispatchError = fold(
        ifLeft = { it },
        ifRight = { error("Expected Left but was Right: $it") }
    )

    /**
     * Returns the right value from an [Either] or fails fast for fixture setup.
     *
     * @receiver Either used while arranging deterministic test fixtures.
     * @return Right value.
     */
    private fun <L, T> Either<L, T>.orError(): T = fold(
        ifLeft = { error("Unexpected fixture mapping failure: $it") },
        ifRight = { it }
    )
}





