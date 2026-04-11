package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.util.Base64

/**
 * [ChallengeSigner] that loads a PEM-encoded private key and signs challenges with RSA-SHA256.
 *
 * The worker auth contract currently expects RSA signatures, so non-RSA keys are rejected with
 * a logical error before the signing operation is attempted.
 */
class PemChallengeSigner(
    private val privateKeyPem: String
) : ChallengeSigner {
    override fun sign(challenge: String): Either<ChallengeSignerError, String> {
        return parsePrivateKey(privateKeyPem).fold(
            { it.left() },
            { privateKey ->
                try {
                    if (!privateKey.algorithm.equals("RSA", ignoreCase = true)) {
                        return@fold ChallengeSignerError.UnsupportedKeyAlgorithm(privateKey.algorithm).left()
                    }

                    val signature = Signature.getInstance("SHA256withRSA")
                    signature.initSign(privateKey)
                    signature.update(challenge.toByteArray(Charsets.UTF_8))
                    Base64.getEncoder().encodeToString(signature.sign()).right()
                } catch (e: Exception) {
                    ChallengeSignerError.SigningFailed(e.message ?: e::class.simpleName.orEmpty()).left()
                }
            }
        )
    }

    /**
     * Parses the configured PEM content into a private key usable for signing.
     *
     * Bouncy Castle is registered lazily because the worker process only needs it when the
     * challenge flow is actually executed.
     *
     * @param pem PEM-encoded private-key material.
     * @return Parsed private key or a logical parse error.
     */
    private fun parsePrivateKey(pem: String): Either<ChallengeSignerError, PrivateKey> {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        return try {
            StringReader(pem).use { stringReader ->
                PEMParser(stringReader).use { parser ->
                    val parsed = parser.readObject()
                    val converter = JcaPEMKeyConverter().setProvider("BC")
                    when (parsed) {
                        // Support both direct PrivateKeyInfo and PEM key-pair encodings.
                        is PrivateKeyInfo -> converter.getPrivateKey(parsed).right()
                        is PEMKeyPair -> converter.getKeyPair(parsed).private.right()
                        else -> ChallengeSignerError.PrivateKeyParseFailed("Unsupported private key format: ${parsed?.javaClass?.name}").left()
                    }
                }
            }
        } catch (e: Exception) {
            ChallengeSignerError.PrivateKeyParseFailed(e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }
}

