package eu.torvian.chatbot.worker.security

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.io.StringReader
import java.security.PrivateKey
import java.security.Security
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

/**
 * Logical failure while parsing the worker's PEM private key.
 *
 * The reason names the problem in terms of the format, never the key material.
 *
 * @property reason What the parser rejected.
 */
internal data class PrivateKeyParseError(val reason: String)

/**
 * Parses the worker's PEM private key into a JCE key.
 *
 * The same key material signs protocol challenges and decrypts credential secrets, so both readers
 * share one parser: a format accepted by one and rejected by the other would fail at the worst
 * possible moment. Bouncy Castle is registered lazily because a worker that never signs or decrypts
 * pays nothing for it.
 */
internal object PemPrivateKeyLoader {
    /**
     * Parses [pem] into a private key.
     *
     * @param pem PEM-encoded private key, in PKCS#8 or key-pair form.
     * @return Either the parse failure or the private key.
     */
    fun load(pem: String): Either<PrivateKeyParseError, PrivateKey> {
        if (Security.getProvider(BOUNCY_CASTLE_PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        return try {
            StringReader(pem).use { stringReader ->
                PEMParser(stringReader).use { parser ->
                    val parsed = parser.readObject()
                    val converter = JcaPEMKeyConverter().setProvider(BOUNCY_CASTLE_PROVIDER)
                    when (parsed) {
                        // Support both direct PrivateKeyInfo and PEM key-pair encodings.
                        is PrivateKeyInfo -> converter.getPrivateKey(parsed).right()
                        is PEMKeyPair -> converter.getKeyPair(parsed).private.right()
                        else -> PrivateKeyParseError(
                            "Unsupported private key format: ${parsed?.javaClass?.name}"
                        ).left()
                    }
                }
            }
        } catch (e: Exception) {
            PrivateKeyParseError(e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    /** Provider name Bouncy Castle registers itself under. */
    private const val BOUNCY_CASTLE_PROVIDER = "BC"
}
