package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Serialization tests for Local MCP server DTOs.
 */
class LocalMCPServerDtosTest {
    /**
     * Verifies regular and secret environment variable lists are serialized independently.
     */
    @Test
    fun `LocalMCPServerDto preserves regular and secret environment lists`() {
        val dto = LocalMCPServerDto(
            id = 1L,
            userId = 2L,
            workerId = 3L,
            name = "server",
            command = "npx",
            environmentVariables = listOf(LocalMCPEnvironmentVariableDto("REG", "a")),
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("SEC", "b")),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
        )

        val encoded = Json.encodeToString(LocalMCPServerDto.serializer(), dto)
        val decoded = Json.decodeFromString(LocalMCPServerDto.serializer(), encoded)

        assertEquals("REG", decoded.environmentVariables.single().key)
        assertEquals("SEC", decoded.secretEnvironmentVariables.single().key)
    }
}


