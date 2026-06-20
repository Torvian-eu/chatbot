package eu.torvian.chatbot.worker.mcp

import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestDraftConnectionCommandData
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
 * Unit tests for [DefaultSignedMcpServerDraftConfigValidator].
 */
class DefaultSignedMcpServerDraftConfigValidatorTest {
    /** Shared JSON codec used to serialize and decode exact signed draft payloads. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies that a matching signed draft payload authorizes the relayed draft command data.
     */
    @Test
    fun `validate authorizes matching signed draft payload`() = runTest {
        val draftRequest = buildDraftRequest()
        val validator = DefaultSignedMcpServerDraftConfigValidator(
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(commandData(draftRequest))

        assertEquals(SignedMcpServerDraftConfigValidationResult.Authorized, result)
    }

    /**
     * Verifies that malformed signed payload JSON is rejected before DTO comparison.
     */
    @Test
    fun `validate rejects malformed signed draft payload`() = runTest {
        val validator = DefaultSignedMcpServerDraftConfigValidator(
            verificationService = StaticVerificationService(emptyList<String>().right())
        )

        val result = validator.validate(commandData(buildDraftRequest(), malformedSignedRequest()))

        assertIs<SignedMcpServerDraftConfigValidationResult.MalformedSignedPayload>(result)
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
        ): arrow.core.Either<VerificationError, List<String>> = result
    }

    /**
     * Builds a valid baseline draft connection request for validator scenarios.
     *
     * @return Draft request fixture.
     */
    private fun buildDraftRequest(): TestLocalMCPServerDraftConnectionRequest = TestLocalMCPServerDraftConnectionRequest(
        workerId = 5L,
        name = "filesystem-draft",
        command = "npx",
        arguments = listOf("-y", "tool"),
        workingDirectory = "C:/data",
        environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
        secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("TOKEN", "secret"))
    )

    /**
     * Converts a signed draft request into the relayed worker command shape under validation.
     *
     * @param draftRequest Draft request whose fields should match the relayed command data.
     * @param signedRequest Detached signed request to embed in the command.
     * @return Worker command data fixture.
     */
    private fun commandData(
        draftRequest: TestLocalMCPServerDraftConnectionRequest,
        signedRequest: SignedRequest = signedRequest(draftRequest)
    ): WorkerMcpServerTestDraftConnectionCommandData = WorkerMcpServerTestDraftConnectionCommandData(
        workerId = draftRequest.workerId,
        name = draftRequest.name,
        command = draftRequest.command,
        arguments = draftRequest.arguments,
        workingDirectory = draftRequest.workingDirectory,
        environmentVariables = draftRequest.environmentVariables,
        secretEnvironmentVariables = draftRequest.secretEnvironmentVariables,
        signedRequest = signedRequest
    )

    /**
     * Builds deterministic detached signed-request metadata whose payload matches [draftRequest].
     *
     * @param draftRequest Draft request to serialize into the signed request payload.
     * @return Signed request fixture.
     */
    private fun signedRequest(draftRequest: TestLocalMCPServerDraftConnectionRequest): SignedRequest = SignedRequest(
        payload = json.encodeToString(TestLocalMCPServerDraftConnectionRequest.serializer(), draftRequest),
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )

    /**
     * Builds detached signed-request metadata with malformed JSON payload.
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