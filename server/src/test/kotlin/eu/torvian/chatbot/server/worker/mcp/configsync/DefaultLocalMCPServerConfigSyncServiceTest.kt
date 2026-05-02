package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateResultData
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Verifies best-effort Local MCP worker config synchronization behavior.
 */
class DefaultLocalMCPServerConfigSyncServiceTest {
    /**
     * Worker protocol dispatch adapter fixture.
     */
    private val dispatchService: LocalMCPRuntimeCommandDispatchService = mockk()

    /**
     * Subject under test.
     */
    private val service = DefaultLocalMCPServerConfigSyncService(dispatchService)

    /**
     * Verifies created servers are forwarded to the worker cache sync command.
     */
    @Test
    fun `sync created delegates to worker create command`() = runTest {
        val server = serverFixture(workerId = 41L, serverId = 101L)
        coEvery { dispatchService.createServer(workerId = 41L, server = server) } returns
            WorkerMcpServerCreateResultData(serverId = 101L).right()

        service.syncCreated(server)

        coVerify(exactly = 1) { dispatchService.createServer(workerId = 41L, server = server) }
    }

    /**
     * Verifies updated servers on the same worker are forwarded to the worker cache update command only.
     */
    @Test
    fun `sync updated on same worker delegates to worker update command only`() = runTest {
        val server = serverFixture(workerId = 42L, serverId = 102L).copy(command = "node")
        coEvery { dispatchService.updateServer(workerId = 42L, server = server) } returns
            WorkerMcpServerUpdateResultData(serverId = 102L).right()

        service.syncUpdated(server, previousWorkerId = 42L)

        coVerify(exactly = 1) { dispatchService.updateServer(workerId = 42L, server = server) }
        coVerify(exactly = 0) { dispatchService.createServer(any(), any()) }
        coVerify(exactly = 0) { dispatchService.deleteServer(any(), any()) }
    }

    /**
     * Verifies reassigned servers are removed from the old worker before being created on the new worker.
     */
    @Test
    fun `sync updated with reassigned worker deletes old cache entry and creates new one`() = runTest {
        val server = serverFixture(workerId = 44L, serverId = 103L).copy(command = "node")
        coEvery { dispatchService.deleteServer(workerId = 43L, serverId = 103L) } returns
            WorkerMcpServerDeleteResultData(serverId = 103L).right()
        coEvery { dispatchService.createServer(workerId = 44L, server = server) } returns
            WorkerMcpServerCreateResultData(serverId = 103L).right()

        service.syncUpdated(server, previousWorkerId = 43L)

        coVerifyOrder {
            dispatchService.deleteServer(workerId = 43L, serverId = 103L)
            dispatchService.createServer(workerId = 44L, server = server)
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

        service.syncDeleted(workerId = 43L, serverId = 103L)

        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 43L, serverId = 103L) }
    }

    /**
     * Verifies worker dispatch failures are logged and swallowed instead of escaping the sync boundary.
     */
    @Test
    fun `dispatch failures on same worker updates are swallowed`() = runTest {
        val server = serverFixture(workerId = 44L, serverId = 104L).copy(command = "node")
        coEvery { dispatchService.createServer(workerId = 44L, server = server) } returns
            WorkerMcpServerCreateResultData(serverId = 104L).right()
        coEvery { dispatchService.updateServer(workerId = 44L, server = server) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 44L)
            ).left()
        coEvery { dispatchService.deleteServer(workerId = 44L, serverId = 104L) } returns
            WorkerMcpServerDeleteResultData(serverId = 104L).right()

        service.syncCreated(server)
        service.syncUpdated(server, previousWorkerId = 44L)
        service.syncDeleted(workerId = 44L, serverId = 104L)

        coVerify(exactly = 1) { dispatchService.createServer(workerId = 44L, server = server) }
        coVerify(exactly = 1) { dispatchService.updateServer(workerId = 44L, server = server) }
        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 44L, serverId = 104L) }
    }

    /**
     * Verifies reassignment cleanup failures are logged and swallowed while the new worker creation still runs.
     */
    @Test
    fun `reassignment delete failure is swallowed`() = runTest {
        val server = serverFixture(workerId = 46L, serverId = 105L)
        coEvery { dispatchService.deleteServer(workerId = 45L, serverId = 105L) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 45L)
            ).left()
        coEvery { dispatchService.createServer(workerId = 46L, server = server) } returns
            WorkerMcpServerCreateResultData(serverId = 105L).right()

        service.syncUpdated(server, previousWorkerId = 45L)

        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 45L, serverId = 105L) }
        coVerify(exactly = 1) { dispatchService.createServer(workerId = 46L, server = server) }
    }

    /**
     * Verifies reassignment creation failures are logged and swallowed after the stale cache entry is removed.
     */
    @Test
    fun `reassignment create failure is swallowed`() = runTest {
        val server = serverFixture(workerId = 48L, serverId = 106L)
        coEvery { dispatchService.deleteServer(workerId = 47L, serverId = 106L) } returns
            WorkerMcpServerDeleteResultData(serverId = 106L).right()
        coEvery { dispatchService.createServer(workerId = 48L, server = server) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 48L)
            ).left()

        service.syncUpdated(server, previousWorkerId = 47L)

        coVerify(exactly = 1) { dispatchService.deleteServer(workerId = 47L, serverId = 106L) }
        coVerify(exactly = 1) { dispatchService.createServer(workerId = 48L, server = server) }
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
}



