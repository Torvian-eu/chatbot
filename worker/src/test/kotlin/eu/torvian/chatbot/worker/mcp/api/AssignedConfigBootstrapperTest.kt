package eu.torvian.chatbot.worker.mcp.api

import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.mcp.InMemoryMcpServerConfigStore
import eu.torvian.chatbot.worker.mcp.SignedMcpServerConfigValidationResult
import eu.torvian.chatbot.worker.mcp.SignedMcpServerConfigValidator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies that assigned-config bootstrap filters invalid server payloads instead of failing the whole refresh.
 */
class AssignedConfigBootstrapperTest {
    /**
     * Confirms that invalid configs are rejected while valid configs still populate the cache.
     */
    @Test
    fun `bootstrap keeps valid configs and drops rejected ones`() = runTest {
        val valid = signedServer(serverId = 1L)
        val missingSignature = signedServer(serverId = 2L).copy(signedRequest = null)
        val validator = RecordingValidator(
            results = mapOf(
                valid.server.id to SignedMcpServerConfigValidationResult.Authorized,
                missingSignature.server.id to SignedMcpServerConfigValidationResult.MissingSignedRequest
            )
        )
        val configStore = InMemoryMcpServerConfigStore()
        val bootstrapper = AssignedConfigBootstrapper(
            mcpServerApi = StaticWorkerMcpServerApi(listOf(valid, missingSignature)),
            signedMcpServerConfigValidator = validator,
            configStore = configStore
        )

        val result = bootstrapper.bootstrap()

        assertTrue(result.isRight())
        assertEquals(listOf(valid.server), configStore.listServers())
        assertEquals(listOf(1L, 2L), validator.validatedServerIds)
    }

    /**
     * Worker API fixture that always returns one preconfigured assigned-server list.
     *
     * @property servers Assigned servers returned to the bootstrapper.
     */
    private class StaticWorkerMcpServerApi(
        private val servers: List<SignedLocalMCPServerDto>
    ) : WorkerMcpServerApi {
        /**
         * @return Preconfigured assigned-server list.
         */
        override suspend fun getAssignedServers() = servers.right()
    }

    /**
     * Validator fixture that records validation order and returns preconfigured outcomes by server ID.
     *
     * @property results Validation result map keyed by Local MCP server identifier.
     */
    private class RecordingValidator(
        private val results: Map<Long, SignedMcpServerConfigValidationResult>
    ) : SignedMcpServerConfigValidator {
        /** Server identifiers validated by the bootstrapper in call order. */
        val validatedServerIds: MutableList<Long> = mutableListOf()

        /**
         * @param server Server whose ID controls the configured validation outcome.
         * @param signedRequest Ignored; outcomes are keyed by server ID.
         * @return Preconfigured validation result for [server].
         */
        override suspend fun validate(
            server: LocalMCPServerDto,
            signedRequest: SignedRequest?
        ): SignedMcpServerConfigValidationResult {
            validatedServerIds += server.id
            return results.getValue(server.id)
        }
    }

    /**
     * Builds a deterministic signed Local MCP server fixture for bootstrap filtering tests.
     *
     * @param serverId Local MCP server identifier used in the fixture.
     * @return Signed Local MCP server wrapper.
     */
    private fun signedServer(serverId: Long): SignedLocalMCPServerDto = SignedLocalMCPServerDto(
        server = LocalMCPServerDto(
            id = serverId,
            userId = 1L,
            workerId = 2L,
            name = "filesystem-$serverId",
            command = "npx",
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
        ),
        signedRequest = SignedRequest(
            payload = "{\"workerId\":2,\"name\":\"filesystem-$serverId\",\"command\":\"npx\"}",
            signature = "signature-base64",
            signerId = "device-1",
            timestamp = 1_700_000_000_000,
            nonce = "nonce-1"
        )
    )
}
