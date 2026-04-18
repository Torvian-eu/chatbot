package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [DummyLocalMCPRuntimeControlService].
 */
class DummyLocalMCPRuntimeControlServiceTest {
    /**
     * Mocked Local MCP server service used for ownership validation behavior.
     */
    private val localMCPServerService: LocalMCPServerService = mockk()

    /**
     * Subject under test.
     */
    private val service = DummyLocalMCPRuntimeControlService(localMCPServerService)

    /**
     * Configures ownership validation to succeed for all operations.
     */
    /**
     * Stubs ownership checks so runtime-control methods can proceed.
     */
    private fun stubOwnershipSuccess() {
        coEvery { localMCPServerService.validateOwnership(any(), any()) } returns Either.Right(Unit)
    }

    /**
     * Verifies successful dummy start behavior.
     */
    @Test
    fun `startServer returns success when ownership validation passes`() = runTest {
        stubOwnershipSuccess()

        val result = service.startServer(userId = 1L, serverId = 10L)

        assertIs<Either.Right<Unit>>(result)
    }

    /**
     * Verifies successful dummy stop behavior.
     */
    @Test
    fun `stopServer returns success when ownership validation passes`() = runTest {
        stubOwnershipSuccess()

        val result = service.stopServer(userId = 1L, serverId = 10L)

        assertIs<Either.Right<Unit>>(result)
    }

    /**
     * Verifies deterministic test-connection payload values.
     */
    @Test
    fun `testConnection returns deterministic dummy payload`() = runTest {
        stubOwnershipSuccess()

        val result = service.testConnection(userId = 1L, serverId = 10L)

        assertIs<Either.Right<TestLocalMCPServerConnectionResponse>>(result)
        val payload = result.value
        assertEquals(10L, payload.serverId)
        assertEquals(true, payload.success)
        assertEquals(3, payload.discoveredToolCount)
        assertEquals(
            "Dummy MCP runtime control implementation; real worker dispatch not yet enabled",
            payload.message
        )
    }

    /**
     * Verifies deterministic empty refresh payload values.
     */
    @Test
    fun `refreshTools returns deterministic empty diff payload`() = runTest {
        stubOwnershipSuccess()

        val result = service.refreshTools(userId = 1L, serverId = 10L)

        assertIs<Either.Right<RefreshMCPToolsResponse>>(result)
        val payload = result.value
        assertEquals(0, payload.addedTools.size)
        assertEquals(0, payload.updatedTools.size)
        assertEquals(0, payload.deletedTools.size)
    }
}





