package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the MCP tool-call specific worker protocol mapping helpers.
 */
class WorkerMcpToolCallProtocolMappingsTest {
    /**
     * Ensures a local request maps into a typed command-request payload.
     */
    @Test
    fun `request maps to command request payload`() {
        val request = LocalMCPToolCallRequest(
            toolCallId = 42,
            serverId = 7,
            toolName = "searchDocs",
            inputJson = "{\"query\":\"ktor\"}"
        )

        val payload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_TOOL_CALL, payload.commandType)
        assertEquals(request, decodeProtocolPayload<LocalMCPToolCallRequest>(payload.data, "LocalMCPToolCallRequest")
            .getOrElse { error("Failed to decode mapped request data: $it") })
    }

    /**
     * Ensures a command-request payload can be mapped back to the local request DTO.
     */
    @Test
    fun `command request payload maps back to local request`() {
        val request = LocalMCPToolCallRequest(
            toolCallId = 77,
            serverId = 3,
            toolName = "listFiles",
            inputJson = "{\"path\":\"/tmp\"}"
        )
        val payload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected mapping success: $it") }

        val decoded = payload.toLocalMcpToolCallRequest()
            .getOrElse { mappingError -> error("Expected reverse mapping success: $mappingError") }

        assertEquals(request, decoded)
    }

    /**
     * Ensures a local result maps into a typed command-result payload with the right status.
     */
    @Test
    fun `result maps to command result payload`() {
        val result = LocalMCPToolCallResult(
            toolCallId = 99,
            output = "{\"ok\":true}",
            isError = false
        )

        val payload = result.toWorkerCommandResultPayload()
            .getOrElse { error("Expected mapping success: $it") }

        assertEquals(WorkerCommandResultStatuses.SUCCESS, payload.status)
        val decoded = decodeProtocolPayload<LocalMCPToolCallResult>(payload.data, "LocalMCPToolCallResult")
            .getOrElse { error("Failed to decode mapped result data: $it") }
        assertFalse(decoded.isError)
        assertEquals(result, decoded)
    }

    /**
     * Ensures error results preserve the error status flag in the typed payload.
     */
    @Test
    fun `error result maps to error status`() {
        val result = LocalMCPToolCallResult(
            toolCallId = 101,
            isError = true,
            errorMessage = "boom"
        )

        val payload = result.toWorkerCommandResultPayload()
            .getOrElse { error("Expected mapping success: $it") }

        assertEquals(WorkerCommandResultStatuses.ERROR, payload.status)
        val decoded = decodeProtocolPayload<LocalMCPToolCallResult>(payload.data, "LocalMCPToolCallResult")
            .getOrElse { error("Failed to decode mapped result data: $it") }
        assertTrue(decoded.isError)
        assertEquals(result, decoded)
    }

    /**
     * Ensures direct `tool.call` payloads do not decode as MCP requests.
     */
    @Test
    fun `direct tool call payload fails reverse mapping to local mcp request`() {
        val payload = WorkerCommandRequestPayload(
            commandType = WorkerProtocolCommandTypes.TOOL_CALL,
            data = encodeProtocolPayload(
                LocalMCPToolCallRequest(
                    toolCallId = 88,
                    serverId = 4,
                    toolName = "searchDocs",
                    inputJson = "{\"query\":\"ktor\"}"
                ),
                "LocalMCPToolCallRequest"
            ).getOrElse { error("Failed to encode test payload: $it") }
        )

        val error = payload.toLocalMcpToolCallRequest().fold(
            ifLeft = { it },
            ifRight = { error("Expected reverse mapping to fail for direct tool.call") }
        )

        when (error) {
            is WorkerMcpToolCallProtocolMappingError.InvalidCommandType -> {
                assertEquals(WorkerProtocolCommandTypes.MCP_TOOL_CALL, error.expected)
                assertEquals(WorkerProtocolCommandTypes.TOOL_CALL, error.actual)
            }

            else -> error("Expected InvalidCommandType, got $error")
        }
    }
}
