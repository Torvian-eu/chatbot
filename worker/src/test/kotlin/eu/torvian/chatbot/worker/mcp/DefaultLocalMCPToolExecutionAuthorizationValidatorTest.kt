package eu.torvian.chatbot.worker.mcp

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerificationService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [DefaultLocalMCPToolExecutionAuthorizationValidator].
 */
class DefaultLocalMCPToolExecutionAuthorizationValidatorTest {
    /** Shared JSON codec used to serialize and deserialize exact signed authorization payloads. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies that a valid signed authorization matching the relayed request is accepted.
     */
    @Test
    fun `validate returns authorized when signed payload matches request`() = runTest {
        val request = buildRequest()
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            json = json,
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(request)

        assertEquals(LocalMCPToolExecutionAuthorizationValidationResult.Authorized, result)
    }

    /**
     * Verifies that mismatched request fields are rejected with explicit diagnostics.
     */
    @Test
    fun `validate rejects request mismatch`() = runTest {
        val request = buildRequest(inputJson = "{\"query\":\"changed\"}")
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            json = json,
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(request)

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.RequestMismatch>(result)
        assertEquals(listOf("input"), rejection.mismatchedFields)
    }

    /**
     * Verifies that denied signed authorizations are rejected even when the signature is otherwise valid.
     */
    @Test
    fun `validate rejects denied authorization`() = runTest {
        val deniedAuthorization = buildAuthorization(approved = false, denialReason = "User denied")
        val request = buildRequest(
            approved = false,
            denialReason = "User denied",
            signedAuthorization = signedRequest(deniedAuthorization)
        )
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            json = json,
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(request)

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.Denied>(result)
        assertEquals("User denied", rejection.denialReason)
    }

    /**
     * Verifies that verification failures are mapped to worker-facing authorization rejections.
     */
    @Test
    fun `validate rejects unknown signer`() = runTest {
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            json = json,
            verificationService = StaticVerificationService(
                VerificationError.UnknownSigner(signerId = "device-9").left()
            )
        )

        val result = validator.validate(buildRequest())

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.UnknownSigner>(result)
        assertEquals("device-9", rejection.signerId)
    }

    /**
     * Fixed verification service used to make validator tests deterministic.
     *
     * @property result Verification result returned for every signed request.
     */
    private class StaticVerificationService(
        private val result: arrow.core.Either<VerificationError, List<String>>
    ) : VerificationService {
        /**
         * @param signedRequest Detached signed request to validate.
         * @param options Verification options supplied by the validator.
         * @return Preconfigured verification result.
         */
        override suspend fun verify(
            signedRequest: SignedRequest,
            options: VerificationOptions
        ): arrow.core.Either<VerificationError, List<String>> {
            return result
        }
    }

    /**
     * Builds a worker request fixture aligned with [buildAuthorization] by default.
     *
     * @param inputJson Exact JSON argument string to relay to the worker.
     * @param approved Approval flag relayed with the request.
     * @param denialReason Optional denial reason relayed with the request.
     * @param signedAuthorization Detached signed authorization to attach to the request.
     * @return Local MCP worker request fixture.
     */
    private fun buildRequest(
        inputJson: String? = "{\"query\":\"ktor\"}",
        approved: Boolean = true,
        denialReason: String? = null,
        signedAuthorization: SignedRequest = signedRequest(buildAuthorization())
    ): LocalMCPToolCallRequest {
        return LocalMCPToolCallRequest(
            toolCallId = 123,
            sessionId = 1,
            messageId = 2,
            toolDefinitionId = 3,
            toolName = "searchDocs",
            serverId = 10,
            mcpToolName = "search_docs",
            inputJson = inputJson,
            approved = approved,
            denialReason = denialReason,
            signedAuthorization = signedAuthorization
        )
    }

    /**
     * Builds the signed authorization payload that the worker should compare against the relayed request.
     *
     * @param approved Whether the authorization approves execution.
     * @param denialReason Optional denial reason to include in the payload.
     * @return Authorization DTO fixture.
     */
    private fun buildAuthorization(
        approved: Boolean = true,
        denialReason: String? = null
    ): LocalMCPToolExecutionAuthorization {
        return LocalMCPToolExecutionAuthorization(
            toolCallId = 123,
            sessionId = 1,
            messageId = 2,
            toolDefinitionId = 3,
            toolName = "searchDocs",
            serverId = 10,
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
    private fun signedRequest(
        authorization: LocalMCPToolExecutionAuthorization
    ): SignedRequest = SignedRequest(
        payload = json.encodeToString(LocalMCPToolExecutionAuthorization.serializer(), authorization),
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}