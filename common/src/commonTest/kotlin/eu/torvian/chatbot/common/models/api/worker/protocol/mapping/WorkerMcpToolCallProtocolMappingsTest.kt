package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the MCP tool-call specific worker protocol mapping helpers.
 */
class WorkerMcpToolCallProtocolMappingsTest {
    /**
     * Ensures a signed Local MCP execution request maps into a typed command-request payload.
     */
    @Test
    fun `request maps to command request payload`() {
        val request = signedExecutionRequest(buildAuthorization())

        val payload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected mapping success: $it") }

        assertEquals(WorkerProtocolCommandTypes.MCP_TOOL_CALL, payload.commandType)
        assertEquals(
            request,
            decodeProtocolPayload<SignedLocalMCPToolExecutionRequest>(
                payload.data,
                "SignedLocalMCPToolExecutionRequest"
            ).getOrElse { error("Failed to decode mapped request data: $it") }
        )
    }

    /**
     * Ensures a command-request payload can be mapped back to the signed Local MCP execution request DTO.
     */
    @Test
    fun `command request payload maps back to local request`() {
        val request = signedExecutionRequest(
            buildAuthorization(
                toolCallId = 77,
                sessionId = 101,
                messageId = 201,
                toolDefinitionId = 301,
                toolName = "listFiles",
                serverId = 3,
                mcpToolName = "list_files",
                input = "{\"path\":\"/tmp\"}"
            )
        )
        val payload = request.toWorkerCommandRequestPayload()
            .getOrElse { error("Expected mapping success: $it") }

        val decoded = payload.toSignedLocalMcpToolExecutionRequest()
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
     * Ensures direct `tool.call` payloads do not decode as signed Local MCP execution requests.
     */
    @Test
    fun `direct tool call payload fails reverse mapping to local mcp request`() {
        val payload = WorkerCommandRequestPayload(
            commandType = WorkerProtocolCommandTypes.TOOL_CALL,
            data = encodeProtocolPayload(
                signedExecutionRequest(
                    buildAuthorization(
                        toolCallId = 88,
                        sessionId = 102,
                        messageId = 202,
                        toolDefinitionId = 302,
                        serverId = 4
                    )
                ),
                "SignedLocalMCPToolExecutionRequest"
            ).getOrElse { error("Failed to encode test payload: $it") }
        )

        val error = payload.toSignedLocalMcpToolExecutionRequest().fold(
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

    /**
     * Builds a valid baseline Local MCP execution authorization for protocol mapping assertions.
     *
     * @param toolCallId Persisted tool-call identifier carried by the authorization.
     * @param sessionId Session identifier carried by the authorization.
     * @param messageId Assistant message identifier carried by the authorization.
     * @param toolDefinitionId Local MCP tool definition identifier carried by the authorization.
     * @param toolName User-visible tool name carried by the authorization.
     * @param serverId Local MCP server identifier carried by the authorization.
     * @param mcpToolName Runtime MCP tool name carried by the authorization.
     * @param input Exact JSON argument string carried by the authorization.
     * @return Authorization DTO fixture.
     */
    private fun buildAuthorization(
        toolCallId: Long = 42,
        sessionId: Long = 100,
        messageId: Long = 200,
        toolDefinitionId: Long = 300,
        toolName: String = "searchDocs",
        serverId: Long = 7,
        mcpToolName: String = "search_docs",
        input: String? = "{\"query\":\"ktor\"}"
    ): LocalMCPToolExecutionAuthorization = LocalMCPToolExecutionAuthorization(
        toolCallId = toolCallId,
        sessionId = sessionId,
        messageId = messageId,
        toolDefinitionId = toolDefinitionId,
        toolName = toolName,
        serverId = serverId,
        mcpToolName = mcpToolName,
        input = input,
        approved = true,
        denialReason = null
    )

    /**
     * Wraps one signed request as the transport DTO used by the worker command protocol.
     *
     * @param authorization Authorization payload whose signed JSON becomes the detached request payload.
     * @return Signed Local MCP execution request fixture.
     */
    private fun signedExecutionRequest(
        authorization: LocalMCPToolExecutionAuthorization
    ): SignedLocalMCPToolExecutionRequest = SignedLocalMCPToolExecutionRequest(
        signedRequest = signedRequest(authorization)
    )

    /**
     * Builds deterministic detached signed-request metadata for protocol mapping assertions.
     *
     * @param authorization Authorization payload to serialize into the signed request payload.
     * @return Signed request fixture.
     */
    private fun signedRequest(authorization: LocalMCPToolExecutionAuthorization): SignedRequest = SignedRequest(
        payload = Json.encodeToString(
            LocalMCPToolExecutionAuthorization.serializer(),
            authorization
        ),
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}
