package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateResultData
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies low-level Local MCP worker config synchronization behavior.
 */
class DefaultLocalMCPServerWorkerSyncServiceTest {
    /**
     * Worker protocol dispatch adapter fixture.
     */
    private val dispatchService: LocalMCPRuntimeCommandDispatchService = mockk()

    /**
     * Subject under test.
     */
    private val service = DefaultLocalMCPServerWorkerSyncService(dispatchService)

    /**
     * Verifies created servers are forwarded to the worker cache sync command.
     */
    @Test
    fun `sync created delegates to worker create command`() = runTest {
        val signedServer = signedServerFixture(workerId = 41L, serverId = 101L)
        coEvery { dispatchService.createServer(workerId = 41L, signedServer = signedServer) } returns
            WorkerMcpServerCreateResultData(serverId = 101L).right()

        val result = service.syncCreated(signedServer)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { dispatchService.createServer(workerId = 41L, signedServer = signedServer) }
    }

    /**
     * Verifies updated servers on the same worker are forwarded to the worker cache update command only.
     */
    @Test
    fun `sync updated on same worker delegates to worker update command only`() = runTest {
        val signedServer = signedServerFixture(workerId = 42L, serverId = 102L).copy(
            server = serverFixture(workerId = 42L, serverId = 102L).copy(command = "node")
        )
        coEvery { dispatchService.updateServer(workerId = 42L, signedServer = signedServer) } returns
            WorkerMcpServerUpdateResultData(serverId = 102L).right()

        val result = service.syncUpdated(signedServer, previousWorkerId = 42L)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { dispatchService.updateServer(workerId = 42L, signedServer = signedServer) }
        coVerify(exactly = 0) { dispatchService.createServer(any(), any()) }
        coVerify(exactly = 0) { dispatchService.deleteServer(any(), any()) }
    }

    /**
     * Verifies reassigned servers are removed from the old worker before being created on the new worker.
     */
    @Test
    fun `sync updated with reassigned worker deletes old cache entry and creates new one`() = runTest {
        val signedServer = signedServerFixture(workerId = 44L, serverId = 103L).copy(
            server = serverFixture(workerId = 44L, serverId = 103L).copy(command = "node")
        )
        coEvery { dispatchService.deleteServer(workerId = 43L, serverId = 103L) } returns
            WorkerMcpServerDeleteResultData(serverId = 103L).right()
        coEvery { dispatchService.createServer(workerId = 44L, signedServer = signedServer) } returns
            WorkerMcpServerCreateResultData(serverId = 103L).right()

        val result = service.syncUpdated(signedServer, previousWorkerId = 43L)

        assertTrue(result.isRight())
        coVerifyOrder {
            dispatchService.deleteServer(workerId = 43L, serverId = 103L)
            dispatchService.createServer(workerId = 44L, signedServer = signedServer)
        }
        coVerify(exactly = 0) { dispatchService.updateServer(any(), any()) }
    }

    /**
     * Verifies deleted servers are forwarded to the worker cache sync command.
     */
    @Test
    fun `sync deleted delegates to worker delete command`() = runTest {
        coEvery { dispatchService.deleteServer(workerId = 43L, serverId = 103L) } returns
            WorkerMcpServerDeleteResultData(serverId = 103L).right()

        val result = service.syncDeleted(workerId = 43L, serverId = 103L)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 43L, serverId = 103L) }
    }

    /**
     * Verifies same-worker update dispatch failures are returned to the caller.
     */
    @Test
    fun `dispatch failures on same worker updates are returned`() = runTest {
        val signedServer = signedServerFixture(workerId = 44L, serverId = 104L).copy(
            server = serverFixture(workerId = 44L, serverId = 104L).copy(command = "node")
        )
        coEvery { dispatchService.updateServer(workerId = 44L, signedServer = signedServer) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 44L)
            ).left()

        val result = service.syncUpdated(signedServer, previousWorkerId = 44L)

        assertTrue(result.isLeft())
        coVerify(exactly = 1) { dispatchService.updateServer(workerId = 44L, signedServer = signedServer) }
        coVerify(exactly = 0) { dispatchService.createServer(any(), any()) }
        coVerify(exactly = 0) { dispatchService.deleteServer(any(), any()) }
    }

    /**
     * Verifies reassignment cleanup failures short-circuit before create runs.
     */
    @Test
    fun `reassignment delete failure is returned`() = runTest {
        val signedServer = signedServerFixture(workerId = 46L, serverId = 105L)
        coEvery { dispatchService.deleteServer(workerId = 45L, serverId = 105L) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 45L)
            ).left()

        val result = service.syncUpdated(signedServer, previousWorkerId = 45L)

        assertTrue(result.isLeft())
        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 45L, serverId = 105L) }
        coVerify(exactly = 0) { dispatchService.createServer(workerId = 46L, signedServer = signedServer) }
    }

    /**
     * Verifies reassignment creation failures are returned after stale-cache deletion succeeds.
     */
    @Test
    fun `reassignment create failure is returned`() = runTest {
        val signedServer = signedServerFixture(workerId = 48L, serverId = 106L)
        coEvery { dispatchService.deleteServer(workerId = 47L, serverId = 106L) } returns
            WorkerMcpServerDeleteResultData(serverId = 106L).right()
        coEvery { dispatchService.createServer(workerId = 48L, signedServer = signedServer) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 48L)
            ).left()

        val result = service.syncUpdated(signedServer, previousWorkerId = 47L)

        assertTrue(result.isLeft())
        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 47L, serverId = 106L) }
        coVerify(exactly = 1) { dispatchService.createServer(workerId = 48L, signedServer = signedServer) }
    }

    /**
     * Builds a deterministic Local MCP server fixture for sync assertions.
     *
     * @param workerId Assigned worker identifier.
     * @param serverId Local MCP server identifier.
     * @return Local MCP server fixture.
     */
    private fun serverFixture(workerId: Long, serverId: Long): LocalMCPServerDto = LocalMCPServerDto(
        id = serverId,
        userId = 9L,
        workerId = workerId,
        name = "filesystem",
        command = "npx",
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )

    /**
     * Builds a deterministic signed-server wrapper for config-sync tests.
     *
     * @param workerId Assigned worker identifier.
     * @param serverId Local MCP server identifier.
     * @return Signed Local MCP server fixture.
     */
    private fun signedServerFixture(workerId: Long, serverId: Long): SignedLocalMCPServerDto = SignedLocalMCPServerDto(
        server = serverFixture(workerId = workerId, serverId = serverId),
        signedRequest = SignedRequest(
            payload = "{\"workerId\":$workerId,\"name\":\"filesystem\",\"command\":\"npx\"}",
            signature = "signature-base64",
            signerId = "device-1",
            timestamp = 1_700_000_000_000,
            nonce = "nonce-1"
        )
    )
}



