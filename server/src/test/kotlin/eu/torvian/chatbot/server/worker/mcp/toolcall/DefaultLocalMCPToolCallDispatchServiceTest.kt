package eu.torvian.chatbot.server.worker.mcp.toolcall

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchSuccess
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies Local MCP tool calls are dispatched through the worker protocol adapter.
 */
class DefaultLocalMCPToolCallDispatchServiceTest {
    private val workerCommandDispatchService: WorkerCommandDispatchService = mockk()
    private val service = DefaultLocalMCPToolCallDispatchService(workerCommandDispatchService)

    init {
        every { workerCommandDispatchService.defaultTimeout } returns 30.seconds
    }

    /**
     * Verifies that successful worker results are decoded back into the shared Local MCP DTO.
     */
    @Test
    fun `dispatch tool call decodes successful result`() = runTest {
        val signedRequest = signedRequest()
        val request = SignedLocalMCPToolExecutionRequest(signedRequest = signedRequest)
        val payloadSlot = slot<WorkerCommandRequestPayload>()
        val expectedResult = LocalMCPToolCallResult(
            toolCallId = 44L,
            output = "{\"files\":[]}",
            isError = false
        )

        coEvery { workerCommandDispatchService.dispatch(11L, capture(payloadSlot), any()) } returns
                WorkerCommandDispatchSuccess(
                    workerId = 11L,
                    interactionId = "interaction-1",
                    commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL,
                    result = expectedResult.toWorkerCommandResultPayload().orError()
                ).right()

        val result = service.dispatchToolCall(workerId = 11L, request = request).requireRight()

        assertEquals(expectedResult, result)
        assertEquals(WorkerProtocolCommandTypes.MCP_TOOL_CALL, payloadSlot.captured.commandType)
    }

    /**
     * Verifies that worker-side tool failures remain visible as tool-result errors.
     */
    @Test
    fun `dispatch tool call decodes worker error result`() = runTest {
        val request = SignedLocalMCPToolExecutionRequest(signedRequest = signedRequest())
        val expectedResult = LocalMCPToolCallResult(
            toolCallId = 45L,
            output = null,
            isError = true,
            errorMessage = "tool failed"
        )

        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
                WorkerCommandDispatchSuccess(
                    workerId = 12L,
                    interactionId = "interaction-2",
                    commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL,
                    result = expectedResult.toWorkerCommandResultPayload().orError()
                ).right()

        val result = service.dispatchToolCall(workerId = 12L, request = request).requireRight()

        assertEquals(expectedResult, result)
        assertEquals(true, result.isError)
        assertEquals("tool failed", result.errorMessage)
    }

    /**
     * Verifies that dispatch lifecycle failures are returned as typed adapter errors.
     */
    @Test
    fun `dispatch tool call maps worker dispatch failure`() = runTest {
        val request = SignedLocalMCPToolExecutionRequest(signedRequest = signedRequest())

        coEvery { workerCommandDispatchService.dispatch(any(), any(), any()) } returns
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 12L).left()

        val error = service.dispatchToolCall(workerId = 12L, request = request).requireLeft()

        assertIs<LocalMCPToolCallDispatchError.DispatchFailed>(error)
        assertIs<WorkerCommandDispatchError.WorkerNotConnected>(error.error)
    }

    /**
     * Decodes a successful worker payload or raises the underlying failure for test assertions.
     */
    private fun <L, R> Either<L, R>.requireRight(): R = fold(
        ifLeft = { error("Expected Right but got Left: $it") },
        ifRight = { it }
    )

    /**
     * Returns the left value or fails the test.
     */
    private fun <L, R> Either<L, R>.requireLeft(): L = fold(
        ifLeft = { it },
        ifRight = { error("Expected Left but got Right: $it") }
    )

    /**
     * Encodes a worker command result payload or fails the test.
     */
    private fun <L, R> Either<L, R>.orError(): R = fold(
        ifLeft = { error("Unexpected mapping failure: $it") },
        ifRight = { it }
    )

    /**
     * Builds deterministic detached signed-request metadata for worker-dispatch assertions.
     *
     * @return Signed request fixture.
     */
    private fun signedRequest(): SignedRequest = SignedRequest(
        payload = "{\"toolCallId\":44}",
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}




