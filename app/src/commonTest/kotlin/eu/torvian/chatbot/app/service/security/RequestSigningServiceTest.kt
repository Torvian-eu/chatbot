package eu.torvian.chatbot.app.service.security

import arrow.core.Either
import eu.torvian.chatbot.app.service.auth.DeviceIdentityError
import eu.torvian.chatbot.app.service.auth.DeviceIdentityService
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.security.AsymmetricCryptoError
import eu.torvian.chatbot.common.security.AsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies detached request-signing behavior.
 */
class RequestSigningServiceTest {

    /** Shared JSON codec used to reproduce the exact payload strings expected from production signing code. */
    private val json = Json

    /**
     * Confirms that request signing preserves the exact JSON payload and canonical signing-string format.
     */
    @Test
    fun `signRequest serializes payload and signs canonical request string`() = runTest {
        val request = CreateLocalMCPServerRequest(
            workerId = 42L,
            name = "filesystem",
            command = "npx",
            arguments = listOf("-y", "@modelcontextprotocol/server-filesystem")
        )
        val keyPair = AsymmetricKeyPair(publicKey = byteArrayOf(1), privateKey = byteArrayOf(9, 8, 7))
        val deviceIdentityService = FakeDeviceIdentityService(deviceId = "device-123", keyPair = keyPair)
        val cryptoProvider = RecordingCryptoProvider(signature = "signature-abc")
        val service = DefaultRequestSigningService(
            deviceIdentityService = deviceIdentityService,
            cryptoProvider = cryptoProvider,
            json = json,
            currentTimeProvider = { 1_700_000_000_123L },
            nonceGenerator = { "nonce-xyz" }
        )

        val result = service.signRequest(request, CreateLocalMCPServerRequest.serializer())

        assertTrue(result is Either.Right)
        val signedRequest = result.value
        val expectedPayload = json.encodeToString(CreateLocalMCPServerRequest.serializer(), request)
        assertEquals(expectedPayload, signedRequest.payload)
        assertEquals("signature-abc", signedRequest.signature)
        assertEquals("device-123", signedRequest.signerId)
        assertEquals(1_700_000_000_123L, signedRequest.timestamp)
        assertEquals("nonce-xyz", signedRequest.nonce)
        assertEquals(
            "1700000000123|nonce-xyz|device-123|$expectedPayload",
            cryptoProvider.lastSignedData
        )
        assertContentEquals(keyPair.privateKey, assertNotNull(cryptoProvider.lastPrivateKey))
    }

    /**
     * Device-identity fake that always returns the provided signer information.
     *
     * @property deviceId Stable signer identifier returned to the service under test.
     * @property keyPair Persistent signing keys returned to the service under test.
     */
    private class FakeDeviceIdentityService(
        private val deviceId: String,
        private val keyPair: AsymmetricKeyPair
    ) : DeviceIdentityService {
        override suspend fun getOrCreateDeviceId(): Either<DeviceIdentityError, String> = Either.Right(deviceId)

        override suspend fun getOrCreateSigningKeyPair(): Either<DeviceIdentityError, AsymmetricKeyPair> = Either.Right(keyPair)
    }

    /**
     * Crypto-provider fake that records the canonical data it was asked to sign.
     *
     * @property signature Signature string returned from every signing request.
     */
    private class RecordingCryptoProvider(
        private val signature: String
    ) : AsymmetricCryptoProvider {
        /** Last canonical signing string received from the service under test. */
        var lastSignedData: String? = null
            private set

        /** Last private key received from the service under test. */
        var lastPrivateKey: ByteArray? = null
            private set

        override suspend fun generateKeyPair(): Either<AsymmetricCryptoError, AsymmetricKeyPair> {
            return Either.Left(AsymmetricCryptoError.KeyGenerationFailed("Unused in request-signing tests"))
        }

        override suspend fun sign(data: String, privateKey: ByteArray): Either<AsymmetricCryptoError, String> {
            lastSignedData = data
            lastPrivateKey = privateKey
            return Either.Right(signature)
        }

        override suspend fun verify(
            data: String,
            signature: String,
            publicKey: ByteArray
        ): Either<AsymmetricCryptoError, Boolean> {
            return Either.Left(AsymmetricCryptoError.SignatureVerificationFailed("Unused in request-signing tests"))
        }
    }
}