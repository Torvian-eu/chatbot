package eu.torvian.chatbot.server.service.core.toolcall

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for [ToolCallApprovalSubmission.LocalMcpSigned].
 */
class ToolCallApprovalSubmissionTest {
    /**
     * Verifies that valid Local MCP signed approvals expose correlation metadata from the shared decoder.
     */
    @Test
    fun `LocalMcpSigned exposes tool call approval metadata from signed payload`() {
        val authorization = buildAuthorization(approved = false, denialReason = "User denied")

        val submission = ToolCallApprovalSubmission.LocalMcpSigned(signedRequest(authorization))

        assertEquals(authorization.toolCallId, submission.toolCallId)
        assertFalse(submission.approved)
        assertEquals("User denied", submission.denialReason)
    }

    /**
     * Verifies that malformed Local MCP signed approvals keep the existing safe fallback metadata.
     */
    @Test
    fun `LocalMcpSigned falls back to safe defaults when signed payload is malformed`() {
        val submission = ToolCallApprovalSubmission.LocalMcpSigned(malformedSignedRequest())

        assertEquals(-1L, submission.toolCallId)
        assertFalse(submission.approved)
        assertEquals("Failed to decode signed authorization metadata", submission.denialReason)
    }

    /**
     * Builds a valid baseline authorization for Local MCP approval-correlation scenarios.
     *
     * @param approved Whether the signed payload should approve execution.
     * @param denialReason Optional denial reason to include in the payload.
     * @return Authorization DTO fixture.
     */
    private fun buildAuthorization(
        approved: Boolean,
        denialReason: String?
    ): LocalMCPToolExecutionAuthorization {
        return LocalMCPToolExecutionAuthorization(
            toolCallId = 123L,
            sessionId = 1L,
            messageId = 2L,
            toolDefinitionId = 3L,
            toolName = "searchDocs",
            serverId = 10L,
            mcpToolName = "search_docs",
            input = "{\"query\":\"ktor\"}",
            approved = approved,
            denialReason = denialReason
        )
    }

    /**
     * Builds deterministic detached signed-request metadata whose payload matches [authorization].
     *
     * @param authorization Authorization payload to serialize into the signed request.
     * @return Signed request fixture.
     */
    private fun signedRequest(authorization: LocalMCPToolExecutionAuthorization): SignedRequest = SignedRequest(
        payload = Json.encodeToString(LocalMCPToolExecutionAuthorization.serializer(), authorization),
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )

    /**
     * Builds detached signed-request metadata with malformed Local MCP authorization JSON.
     *
     * @return Signed request fixture whose payload cannot be decoded.
     */
    private fun malformedSignedRequest(): SignedRequest = SignedRequest(
        payload = "{not-valid-json",
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}