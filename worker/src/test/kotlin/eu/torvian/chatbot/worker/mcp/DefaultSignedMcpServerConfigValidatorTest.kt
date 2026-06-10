package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import eu.torvian.chatbot.common.security.JvmAsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.buildCanonicalRequestSigningString
import eu.torvian.chatbot.worker.config.TrustedSigner
import eu.torvian.chatbot.worker.service.security.DefaultVerificationService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Verifies worker-side Local MCP config authorization against real detached signatures.
 */
class DefaultSignedMcpServerConfigValidatorTest {
    /** JVM crypto provider used to produce realistic Ed25519 signatures in validator tests. */
    private val cryptoProvider = JvmAsymmetricCryptoProvider()

    /** JSON codec used both for request signing and worker-side payload decoding. */
    private val json = Json { ignoreUnknownKeys = true }

    /** Stable signer identifier used by the test trust-store entry. */
    private val signerId = "trusted-device-1"

    /** Stable permission set required by the verification service fixture. */
    private val permissions = listOf("mcp:write")

    /**
     * Confirms that a matching signed payload authorizes the relayed server DTO.
     */
    @Test
    fun `matching signed create payload authorizes server dto`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()
        val validator = validator(keyPair)

        val result = validator.validate(
            server = serverFixture(request),
            signedRequest = signedRequest(keyPair, request)
        )

        assertEquals(SignedMcpServerConfigValidationResult.Authorized, result)
    }

    /**
     * Confirms that broken detached signatures are rejected before DTO comparison.
     */
    @Test
    fun `invalid signature rejects server dto`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()
        val validator = validator(keyPair)

        val result = validator.validate(
            server = serverFixture(request),
            signedRequest = signedRequest(keyPair, request).copy(signature = "invalid-signature")
        )

        assertIs<SignedMcpServerConfigValidationResult.InvalidSignature>(result)
    }

    /**
     * Confirms that signed payload and relayed DTO mismatches are rejected with explicit field diagnostics.
     */
    @Test
    fun `dto mismatch reports explicit differing field`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()
        val validator = validator(keyPair)

        val result = validator.validate(
            server = serverFixture(request).copy(command = "node"),
            signedRequest = signedRequest(keyPair, request)
        )

        val mismatch = assertIs<SignedMcpServerConfigValidationResult.DtoMismatch>(result)
        assertEquals(listOf("command"), mismatch.mismatchedFields)
    }

    /**
     * Generates a key pair and fails the test if the crypto provider cannot produce one.
     *
     * @return Generated asymmetric key pair for signing and trust-store configuration.
     */
    private suspend fun generateKeyPair(): AsymmetricKeyPair {
        val result = cryptoProvider.generateKeyPair()
        assertTrue(result is Either.Right, "Key-pair generation should succeed for validator tests")
        return result.value
    }

    /**
     * Creates a validator configured with one trusted signer derived from the supplied key pair.
     *
     * @param keyPair Generated key pair whose public key should be trusted.
     * @return Validator configured with real detached-signature verification.
     */
    private fun validator(keyPair: AsymmetricKeyPair): SignedMcpServerConfigValidator =
        DefaultSignedMcpServerConfigValidator(
            json = json,
            verificationService = DefaultVerificationService(
                trustedSigners = listOf(TrustedSigner(signerId, keyPair.publicKey, permissions)),
                cryptoProvider = cryptoProvider
            )
        )

    /**
     * Builds a deterministic Local MCP request fixture whose fields are all worker-authorized.
     *
     * @return Create request fixture used for both signing and DTO construction.
     */
    private fun requestFixture(): CreateLocalMCPServerRequest = CreateLocalMCPServerRequest(
        workerId = 2L,
        name = "filesystem",
        description = "Reads local files",
        command = "npx",
        arguments = listOf("-y", "@modelcontextprotocol/server-filesystem"),
        workingDirectory = "C:/mcp",
        isEnabled = true,
        autoStartOnEnable = true,
        autoStartOnLaunch = false,
        autoStopAfterInactivitySeconds = 300,
        toolNamePrefix = "fs",
        environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
        secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_KEY", "secret"))
    )

    /**
     * Converts a request fixture into the relayed Local MCP server DTO shape used by the worker.
     *
     * @param request Signed request fixture that should match the returned DTO.
     * @return Local MCP server DTO whose request-derived fields mirror [request].
     */
    private fun serverFixture(request: CreateLocalMCPServerRequest): LocalMCPServerDto = LocalMCPServerDto(
        id = 10L,
        userId = 1L,
        workerId = request.workerId,
        name = request.name,
        description = request.description,
        command = request.command,
        arguments = request.arguments,
        workingDirectory = request.workingDirectory,
        isEnabled = request.isEnabled,
        autoStartOnEnable = request.autoStartOnEnable,
        autoStartOnLaunch = request.autoStartOnLaunch,
        autoStopAfterInactivitySeconds = request.autoStopAfterInactivitySeconds,
        toolNamePrefix = request.toolNamePrefix,
        environmentVariables = request.environmentVariables,
        secretEnvironmentVariables = request.secretEnvironmentVariables,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )

    /**
     * Signs the exact serialized request payload with the supplied key pair.
     *
     * @param keyPair Generated key pair whose private key should sign the request.
     * @param request Request DTO that should be serialized and signed byte-for-byte.
     * @return Detached signed request accepted by the worker validator.
     */
    private suspend fun signedRequest(
        keyPair: AsymmetricKeyPair,
        request: CreateLocalMCPServerRequest
    ): SignedRequest {
        val payload = json.encodeToString(request)
        val unsignedRequest = SignedRequest(
            payload = payload,
            signature = "",
            signerId = signerId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            nonce = "nonce-1"
        )
        val signature = cryptoProvider.sign(buildCanonicalRequestSigningString(unsignedRequest), keyPair.privateKey)
        assertTrue(signature is Either.Right, "Signing should succeed for validator tests")
        return unsignedRequest.copy(signature = signature.value)
    }
}
