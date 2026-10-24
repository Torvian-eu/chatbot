package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.worker.security.PemPrivateKeyLoader
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
        return PemPrivateKeyLoader.load(privateKeyPem).fold(
            { error -> ChallengeSignerError.PrivateKeyParseFailed(error.reason).left() },
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
}

