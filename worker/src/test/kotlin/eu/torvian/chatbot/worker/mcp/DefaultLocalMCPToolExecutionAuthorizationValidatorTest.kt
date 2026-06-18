package eu.torvian.chatbot.worker.mcp

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerificationService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [DefaultLocalMCPToolExecutionAuthorizationValidator].
 */
class DefaultLocalMCPToolExecutionAuthorizationValidatorTest {
    /**
     * Verifies that a valid signed authorization is decoded and returned as Authorized.
     */
    @Test
    fun `validate returns authorized with decoded authorization when signature is valid`() = runTest {
        val authorization = buildAuthorization()
        val signedRequest = signedRequest(authorization)
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(signedRequest)

        val authorized = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.Authorized>(result)
        assertEquals(authorization, authorized.authorization)
    }

    /**
     * Verifies that denied signed authorizations are rejected even when the signature is otherwise valid.
     */
    @Test
    fun `validate rejects denied authorization with toolCallId from payload`() = runTest {
        val deniedAuthorization = buildAuthorization(approved = false, denialReason = "User denied")
        val signedRequest = signedRequest(deniedAuthorization)
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(signedRequest)

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.Denied>(result)
        assertEquals("User denied", rejection.denialReason)
        assertEquals(deniedAuthorization.toolCallId, rejection.toolCallId)
    }

    /**
     * Verifies that verification failures are mapped to worker-facing authorization rejections with tool call ID.
     */
    @Test
    fun `validate rejects unknown signer with toolCallId from payload`() = runTest {
        val authorization = buildAuthorization()
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            verificationService = StaticVerificationService(
                VerificationError.UnknownSigner(signerId = "device-9").left()
            )
        )

        val result = validator.validate(signedRequest(authorization))

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.UnknownSigner>(result)
        assertEquals("device-9", rejection.signerId)
        assertEquals(authorization.toolCallId, rejection.toolCallId)
    }

    /**
     * Verifies that expired authorizations are rejected with tool call ID from payload.
     */
    @Test
    fun `validate rejects expired authorization with toolCallId from payload`() = runTest {
        val authorization = buildAuthorization()
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            verificationService = StaticVerificationService(
                VerificationError.Expired(timestamp = 1_700_000_000_000, ageSeconds = 120).left()
            )
        )

        val result = validator.validate(signedRequest(authorization))

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.ExpiredAuthorization>(result)
        assertEquals(authorization.toolCallId, rejection.toolCallId)
    }

    /**
     * Verifies that malformed payloads result in null toolCallId.
     */
    @Test
    fun `validate rejects malformed payload with null toolCallId`() = runTest {
        val validator = DefaultLocalMCPToolExecutionAuthorizationValidator(
            verificationService = StaticVerificationService(emptyList<String>().right())
        )
        val malformedRequest = SignedRequest(
            payload = "{not-valid-json",
            signature = "signature",
            signerId = "device-1",
            timestamp = 1_700_000_000_000,
            nonce = "nonce-1"
        )

        val result = validator.validate(malformedRequest)

        val rejection = assertIs<LocalMCPToolExecutionAuthorizationValidationResult.MalformedSignedPayload>(result)
        assertEquals(null, rejection.toolCallId)
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
     * Builds the signed authorization payload for the worker to validate and execute.
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
        payload = kotlinx.serialization.json.Json.encodeToString(LocalMCPToolExecutionAuthorization.serializer(), authorization),
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}