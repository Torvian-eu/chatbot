package eu.torvian.chatbot.worker.security

import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Verifies that PEM parsing reports a typed failure instead of prose in the error channel.
 *
 * Both the challenge signer and the secret decryptor parse the same key, so the failure type is what
 * keeps a technical message from becoming the reason a caller renders.
 */
class PemPrivateKeyLoaderTest {

    /** Verifies that a PKCS#8 RSA key parses. */
    @Test
    fun `loads a PKCS#8 private key`() {
        val pem = privateKeyPem()

        val key = PemPrivateKeyLoader.load(pem).getOrNull()

        assertEquals("RSA", assertNotNull(key).algorithm)
    }

    /** Verifies that unsupported PEM content is reported as a typed parse failure. */
    @Test
    fun `reports a typed failure for an unsupported block`() {
        val pem = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"

        val error = assertIs<PrivateKeyParseError>(PemPrivateKeyLoader.load(pem).swap().getOrNull())

        assertNotNull(error.reason, "The failure must name the problem")
    }

    /** Verifies that malformed PEM is reported as a typed parse failure rather than an exception. */
    @Test
    fun `reports a typed failure for malformed input`() {
        assertIs<PrivateKeyParseError>(PemPrivateKeyLoader.load("not a pem document").swap().getOrNull())
    }

    /**
     * @return A freshly generated 2048-bit RSA key in PKCS#8 PEM form.
     */
    private fun privateKeyPem(): String {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n"
    }
}
