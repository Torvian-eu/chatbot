package eu.torvian.chatbot.worker.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import eu.torvian.chatbot.common.security.JvmAsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.buildCanonicalRequestSigningString
import eu.torvian.chatbot.worker.config.TrustedSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies worker-side detached signed-request validation behavior against the JVM crypto provider.
 */
class DefaultVerificationServiceTest {
    /** Crypto provider used to create and verify realistic Ed25519 signatures in service tests. */
    private val cryptoProvider = JvmAsymmetricCryptoProvider()

    /** Stable signer identifier shared by generated signed requests and trust-store entries. */
    private val signerId = "trusted-signer-1"

    /** Permission set expected when verification succeeds. */
    private val permissions = listOf("mcp:read", "mcp:write")

    /**
     * Confirms that a signer present in the local trust store can authorize a correctly signed request.
     */
    @Test
    fun `returns permissions for valid trusted signature`() = runTest {
        val keyPair = generateKeyPair()
        val signedRequest = signedRequest(keyPair)
        val service = verificationService(keyPair)

        val result = service.verify(signedRequest)

        assertEquals(permissions, result.getOrNull())
    }

    /**
     * Confirms that signer IDs absent from the injected trust-store list are rejected before crypto verification.
     */
    @Test
    fun `returns unknown signer for untrusted signer id`() = runTest {
        val signedRequest = unsignedSignedRequest(signerId = "missing-signer").copy(signature = "unused-signature")
        val service = DefaultVerificationService(
            trustedSigners = listOf(TrustedSigner(signerId, byteArrayOf(1, 2, 3), permissions)),
            cryptoProvider = cryptoProvider
        )

        val result = service.verify(signedRequest)
        val error = result.swap().getOrNull()

        assertIs<VerificationError.UnknownSigner>(error)
        assertEquals("missing-signer", error.signerId)
    }

    /**
     * Confirms that changing the signed payload invalidates the reconstructed signature base string.
     */
    @Test
    fun `returns invalid signature when payload is changed`() = runTest {
        val keyPair = generateKeyPair()
        val signedRequest = signedRequest(keyPair, payload = "{\"enabled\":true}")
        val service = verificationService(keyPair)

        val result = service.verify(signedRequest.copy(payload = "{\"enabled\":false}"))

        val error = result.swap().getOrNull()
        assertIs<VerificationError.InvalidSignature>(error)
    }

    /**
     * Confirms that changing the detached signature itself is reported as a trust failure.
     */
    @Test
    fun `returns invalid signature when signature is changed`() = runTest {
        val keyPair = generateKeyPair()
        val signedRequest = signedRequest(keyPair)
        val service = verificationService(keyPair)

        val result = service.verify(signedRequest.copy(signature = "invalid-signature"))

        val error = result.swap().getOrNull()
        assertIs<VerificationError.InvalidSignature>(error)
    }

    /**
     * Confirms that persistent signatures can opt out of transient timestamp freshness checks.
     */
    @Test
    fun `ignores old timestamp when expiration check is disabled`() = runTest {
        val keyPair = generateKeyPair()
        val signedRequest = signedRequest(keyPair, timestamp = 1L)
        val service = verificationService(keyPair)

        val result = service.verify(signedRequest, VerificationOptions(checkExpiration = false))

        assertEquals(permissions, result.getOrNull())
    }

    /**
     * Confirms that transient-command verification rejects stale timestamps by default.
     */
    @Test
    fun `returns expired for stale timestamp when expiration check is enabled`() = runTest {
        val keyPair = generateKeyPair()
        val signedRequest = signedRequest(keyPair, timestamp = 1L)
        val service = verificationService(keyPair)

        val result = service.verify(signedRequest)
        val error = result.swap().getOrNull()

        assertIs<VerificationError.Expired>(error)
        assertEquals(1L, error.timestamp)
        assertTrue(error.ageSeconds > 300)
    }

    /**
     * Generates a key pair and fails the test if the crypto provider cannot produce one.
     *
     * @return Generated asymmetric key pair for signing and trust-store configuration.
     */
    private suspend fun generateKeyPair(): AsymmetricKeyPair {
        val result = cryptoProvider.generateKeyPair()
        assertTrue(result is Either.Right, "Key-pair generation should succeed for verification tests")
        return result.value
    }

    /**
     * Creates a service using exactly one trusted signer entry derived from the supplied key pair.
     *
     * @param keyPair Generated key pair whose public key should be trusted.
     * @return Verification service configured with the test trust store and crypto provider.
     */
    private fun verificationService(keyPair: AsymmetricKeyPair): VerificationService =
        DefaultVerificationService(
            trustedSigners = listOf(TrustedSigner(signerId, keyPair.publicKey, permissions)),
            cryptoProvider = cryptoProvider
        )

    /**
     * Builds and signs a detached signed request using the same canonical base string verified by the service.
     *
     * @param keyPair Generated key pair whose private key should produce the request signature.
     * @param payload Exact payload string to include in the signed request.
     * @param timestamp Epoch-millisecond timestamp to include in the signed request.
     * @param nonce Nonce to include in the signed request.
     * @return Signed request whose signature matches its timestamp, nonce, signer ID, and payload.
     */
    private suspend fun signedRequest(
        keyPair: AsymmetricKeyPair,
        payload: String = "{\"operation\":\"test\"}",
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        nonce: String = "nonce-1"
    ): SignedRequest {
        val signedRequest = unsignedSignedRequest(payload = payload, timestamp = timestamp, nonce = nonce)
        val signature = cryptoProvider.sign(buildCanonicalRequestSigningString(signedRequest), keyPair.privateKey)

        assertTrue(signature is Either.Right, "Signing should succeed for verification tests")
        return signedRequest.copy(signature = signature.value)
    }

    /**
     * Creates a signed-request shell before its signature has been calculated.
     *
     * @param payload Exact payload string to include in the request.
     * @param signerId Signer identifier to include in the request.
     * @param timestamp Epoch-millisecond timestamp to include in the request.
     * @param nonce Nonce to include in the request.
     * @return Signed request with an empty signature placeholder.
     */
    private fun unsignedSignedRequest(
        payload: String = "{\"operation\":\"test\"}",
        signerId: String = this.signerId,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        nonce: String = "nonce-1"
    ): SignedRequest = SignedRequest(
        payload = payload,
        signature = "",
        signerId = signerId,
        timestamp = timestamp,
        nonce = nonce
    )
}