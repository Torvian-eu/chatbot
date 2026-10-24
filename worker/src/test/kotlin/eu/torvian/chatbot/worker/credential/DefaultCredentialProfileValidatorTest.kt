package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
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
 * Verifies worker-side credential-profile authorization against real detached signatures.
 */
class DefaultCredentialProfileValidatorTest {

    private val cryptoProvider = JvmAsymmetricCryptoProvider()
    private val json = Json { ignoreUnknownKeys = true }
    private val signerId = "trusted-device-1"
    private val permissions = listOf("credentials:write")

    /**
     * Ensures a matching signed create payload authorizes the relayed profile.
     */
    @Test
    fun `matching signed payload authorizes the profile`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()

        val result = validator(keyPair).validate(profileFixture(request), signedRequest(keyPair, request), 2L)

        assertIs<CredentialProfileValidationResult.Authorized>(result)
    }

    /**
     * Ensures an update-shaped payload is accepted as well, because bootstrap replays both shapes.
     */
    @Test
    fun `signed update payload authorizes the profile`() = runTest {
        val keyPair = generateKeyPair()
        val request = UpdateCredentialProfileRequest(
            workerId = 2L,
            name = "vps",
            description = "prod",
            secretRef = "vps_password",
            command = "/bin/sh",
            args = listOf("-c", "true"),
            channel = CredentialChannel.ASKPASS,
            askpassVariables = listOf("SSH_ASKPASS"),
        )

        val result = validator(keyPair).validate(
            profileFixture(request),
            signedRequest(keyPair, json.encodeToString(request)),
            2L
        )

        assertIs<CredentialProfileValidationResult.Authorized>(result)
    }

    /**
     * Ensures a missing signature rejects the profile.
     */
    @Test
    fun `missing signature is rejected`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()

        val result = validator(keyPair).validate(profileFixture(request), signedRequest = null, workerId = 2L)

        assertIs<CredentialProfileValidationResult.MissingSignedRequest>(result)
    }

    /**
     * Ensures a signature from an untrusted device is rejected.
     */
    @Test
    fun `unknown signer is rejected`() = runTest {
        val keyPair = generateKeyPair()
        val otherKeyPair = generateKeyPair()
        val request = requestFixture()

        val result = validator(keyPair).validate(
            profileFixture(request),
            signedRequest(otherKeyPair, request).copy(signerId = signerId),
            workerId = 2L
        )

        assertIs<CredentialProfileValidationResult.InvalidSignature>(result)
    }

    /**
     * Ensures a relayed profile that disagrees with the signed payload is rejected.
     */
    @Test
    fun `relayed command must match the signed payload`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()

        val result = validator(keyPair).validate(
            profileFixture(request).copy(command = "/bin/other"),
            signedRequest(keyPair, request),
            workerId = 2L
        )

        val rejection = assertIs<CredentialProfileValidationResult.DtoMismatch>(result)
        assertEquals(listOf("command"), rejection.fields)
    }

    /**
     * Ensures a tampered environment map or askpass list breaks the comparison, which is why both are
     * part of the signed payload.
     */
    @Test
    fun `tampered environment or askpass variables are rejected`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()
        val signature = signedRequest(keyPair, request)

        val tamperedEnvironment = validator(keyPair).validate(
            profileFixture(request).copy(environmentVariables = mapOf("SSH_ASKPASS_REQUIRE" to "never")),
            signature,
            workerId = 2L
        )
        val tamperedAskpass = validator(keyPair).validate(
            profileFixture(request).copy(askpassVariables = listOf("GIT_ASKPASS")),
            signature,
            workerId = 2L
        )

        assertEquals(
            listOf("environmentVariables"),
            assertIs<CredentialProfileValidationResult.DtoMismatch>(tamperedEnvironment).fields
        )
        assertEquals(
            listOf("askpassVariables"),
            assertIs<CredentialProfileValidationResult.DtoMismatch>(tamperedAskpass).fields
        )
    }

    /**
     * Ensures a row bound to another worker is rejected before any signature work.
     */
    @Test
    fun `foreign worker row is rejected`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture()

        val result = validator(keyPair).validate(profileFixture(request), signedRequest(keyPair, request), 99L)

        val rejection = assertIs<CredentialProfileValidationResult.ForeignWorker>(result)
        assertEquals(99L, rejection.expected)
        assertEquals(2L, rejection.actual)
    }

    /**
     * Ensures a payload that decodes but violates the shared rules is rejected even when signed.
     */
    @Test
    fun `signed but invalid profile is rejected by the shared rules`() = runTest {
        val keyPair = generateKeyPair()
        val request = requestFixture().copy(askpassVariables = emptyList())

        val result = validator(keyPair).validate(profileFixture(request), signedRequest(keyPair, request), 2L)

        assertIs<CredentialProfileValidationResult.ValidationFailed>(result)
    }

    /**
     * Ensures a reserved deployment key cannot be used as the profile's `envName`.
     */
    @Test
    fun `deployment environment keys are rejected as envName`() = runTest {
        val keyPair = generateKeyPair()
        val request = CreateCredentialProfileRequest(
            workerId = 2L,
            name = "token-cli",
            secretRef = "ref",
            command = "/bin/sh",
            channel = CredentialChannel.ENV,
            envName = "GIT_CONFIG_COUNT",
        )
        val validator = DefaultCredentialProfileValidator(
            verificationService = verificationService(keyPair),
            deploymentEnvironmentKeys = setOf("GIT_CONFIG_COUNT"),
        )

        val result = validator.validate(profileFixture(request), signedRequest(keyPair, request), 2L)

        assertIs<CredentialProfileValidationResult.ValidationFailed>(result)
    }

    /**
     * @return A generated key pair.
     */
    private suspend fun generateKeyPair(): AsymmetricKeyPair {
        val result = cryptoProvider.generateKeyPair()
        assertTrue(result is Either.Right, "Key-pair generation should succeed")
        return result.value
    }

    /**
     * @param keyPair Key pair whose public half is trusted.
     * @return Worker verification service containing exactly that signer.
     */
    private fun verificationService(keyPair: AsymmetricKeyPair): DefaultVerificationService =
        DefaultVerificationService(
            trustedSigners = listOf(TrustedSigner(signerId, keyPair.publicKey, permissions)),
            cryptoProvider = cryptoProvider
        )

    /**
     * @param keyPair Key pair whose public half is trusted.
     * @return Validator under test.
     */
    private fun validator(keyPair: AsymmetricKeyPair): DefaultCredentialProfileValidator =
        DefaultCredentialProfileValidator(verificationService = verificationService(keyPair))

    /**
     * @return A create request matching [profileFixture].
     */
    private fun requestFixture(): CreateCredentialProfileRequest = CreateCredentialProfileRequest(
        workerId = 2L,
        name = "vps",
        description = "prod shell",
        secretRef = "vps_password",
        command = "/bin/sh",
        args = listOf("-c", "true"),
        channel = CredentialChannel.ASKPASS,
        environmentVariables = mapOf("SSH_ASKPASS_REQUIRE" to "force"),
        askpassVariables = listOf("SSH_ASKPASS"),
    )

    /**
     * @param request Request whose fields the relayed profile must mirror.
     * @return The relayed profile DTO.
     */
    private fun profileFixture(request: CreateCredentialProfileRequest): CredentialProfileDto =
        CredentialProfileDto(
            id = 10L,
            workerId = request.workerId,
            name = request.name,
            description = request.description,
            secretRef = request.secretRef,
            command = request.command,
            args = request.args,
            channel = request.channel,
            envName = request.envName,
            fileName = request.fileName,
            askpassUsername = request.askpassUsername,
            isEnabled = request.isEnabled,
            environmentVariables = request.environmentVariables,
            askpassVariables = request.askpassVariables,
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

    /**
     * @param request Update request whose fields the relayed profile must mirror.
     * @return The relayed profile DTO.
     */
    private fun profileFixture(request: UpdateCredentialProfileRequest): CredentialProfileDto =
        CredentialProfileDto(
            id = 10L,
            workerId = request.workerId,
            name = request.name,
            description = request.description,
            secretRef = request.secretRef,
            command = request.command,
            args = request.args,
            channel = request.channel,
            envName = request.envName,
            fileName = request.fileName,
            askpassUsername = request.askpassUsername,
            isEnabled = request.isEnabled,
            environmentVariables = request.environmentVariables,
            askpassVariables = request.askpassVariables,
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

    /**
     * @param keyPair Key pair whose private half signs the payload.
     * @param request Request DTO serialized verbatim into the payload.
     * @return Detached signed request accepted by the worker validator.
     */
    private suspend fun signedRequest(
        keyPair: AsymmetricKeyPair,
        request: CreateCredentialProfileRequest,
    ): SignedRequest = signedRequest(keyPair, json.encodeToString(request))

    /**
     * @param keyPair Key pair whose private half signs the payload.
     * @param payload Exact serialized payload string.
     * @return Detached signed request accepted by the worker validator.
     */
    private suspend fun signedRequest(keyPair: AsymmetricKeyPair, payload: String): SignedRequest {
        val unsignedRequest = SignedRequest(
            payload = payload,
            signature = "",
            signerId = signerId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            nonce = "nonce-1"
        )
        val signature = cryptoProvider.sign(buildCanonicalRequestSigningString(unsignedRequest), keyPair.privateKey)
        assertTrue(signature is Either.Right, "Signing should succeed")
        return unsignedRequest.copy(signature = signature.value)
    }
}
