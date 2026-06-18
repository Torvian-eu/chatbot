package eu.torvian.chatbot.common.security

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for shared signed-request payload decoding helpers.
 */
class SignedRequestPayloadDecodingTest {
    /**
     * Verifies that a valid signed payload is decoded back into the requested DTO type using the default codec.
     */
    @Test
    fun `decodePayload returns decoded dto for valid payload`() {
        val authorization = buildAuthorization()

        val result = signedRequest(authorization).decodePayload<LocalMCPToolExecutionAuthorization>()

        val decoded = assertIs<SignedRequestPayloadDecodingResult.Decoded<LocalMCPToolExecutionAuthorization>>(result)
        assertEquals(authorization, decoded.value)
    }

    /**
     * Verifies that malformed signed payload JSON is reported without throwing when the default codec is used.
     */
    @Test
    fun `decodePayload returns malformed payload for invalid json`() {
        val result = malformedSignedRequest().decodePayload<LocalMCPToolExecutionAuthorization>()

        assertIs<SignedRequestPayloadDecodingResult.MalformedPayload>(result)
    }

    /**
     * Verifies that the nullable convenience helper suppresses incompatible payload failures.
     */
    @Test
    fun `decodePayloadOrNull returns null for malformed payload`() {
        val result = malformedSignedRequest().decodePayloadOrNull<LocalMCPToolExecutionAuthorization>()

        assertEquals(null, result)
    }

    /**
     * Builds a valid baseline Local MCP execution authorization for decoding scenarios.
     *
     * @return Authorization DTO fixture.
     */
    private fun buildAuthorization(): LocalMCPToolExecutionAuthorization = LocalMCPToolExecutionAuthorization(
        toolCallId = 123L,
        sessionId = 1L,
        messageId = 2L,
        toolDefinitionId = 3L,
        toolName = "searchDocs",
        serverId = 10L,
        mcpToolName = "search_docs",
        input = "{\"query\":\"ktor\"}",
        approved = true,
        denialReason = null
    )

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
     * Builds detached signed-request metadata with malformed payload JSON.
     *
     * @return Signed request fixture whose payload cannot be deserialized.
     */
    private fun malformedSignedRequest(): SignedRequest = SignedRequest(
        payload = "{not-valid-json",
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}