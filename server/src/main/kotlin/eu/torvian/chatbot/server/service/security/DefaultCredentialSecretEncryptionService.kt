package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretLimits
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Hybrid envelope implementation of [CredentialSecretEncryptionService].
 *
 * A random AES-256 data key encrypts the value with GCM, and the data key is wrapped with RSA-OAEP
 * under the worker certificate. Two layers are used because a bare RSA wrap cannot carry a secret as
 * large as the shared limit.
 *
 * The OAEP parameter specification is stated explicitly on both ends of the exchange: the JCE default
 * MGF1 digest differs between providers, so an implicit spec would silently produce ciphertext the
 * worker cannot unwrap.
 *
 * @property certificateService Parser used to obtain the worker public key and its fingerprint.
 * @property secureRandom Source of the data key and the GCM nonce.
 */
class DefaultCredentialSecretEncryptionService(
    private val certificateService: CertificateService,
    private val secureRandom: SecureRandom = SecureRandom(),
) : CredentialSecretEncryptionService {

    override fun encrypt(
        plaintext: String,
        workerCertificatePem: String,
    ): Either<CredentialEncryptionError, EncryptedCredentialPayload> = either {
        val value = plaintext.encodeToByteArray()
        if (value.size > CredentialSecretLimits.MAX_SECRET_BYTES) {
            raise(CredentialEncryptionError.ValueTooLarge(value.size, CredentialSecretLimits.MAX_SECRET_BYTES))
        }

        val certificate = runCatching { certificateService.parseCertificate(workerCertificatePem) }
            .getOrElse { failure ->
                raise(CredentialEncryptionError.InvalidCertificate(failure.javaClass.simpleName))
            }
        val publicKey = certificate.publicKey
        if (publicKey.algorithm != RSA_ALGORITHM) {
            raise(CredentialEncryptionError.UnsupportedKey(publicKey.algorithm))
        }

        val dataKey = ByteArray(AES_KEY_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)

        val ciphertext = runCatching {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(dataKey, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            val encrypted = cipher.doFinal(value)
            // The nonce is prefixed so the stored ciphertext is self-contained; without it the worker
            // would need a second column for a value that is useless on its own.
            nonce + encrypted
        }.getOrElse { failure ->
            dataKey.fill(0)
            raise(CredentialEncryptionError.CryptoFailure(failure.javaClass.simpleName))
        }

        val wrappedDek = runCatching {
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
            cipher.init(Cipher.WRAP_MODE, publicKey, OAEP_PARAMETERS)
            cipher.wrap(SecretKeySpec(dataKey, AES_ALGORITHM))
        }.getOrElse { failure ->
            raise(CredentialEncryptionError.CryptoFailure(failure.javaClass.simpleName))
        }.also {
            // The data key has served its purpose once wrapped; the worker unwraps its own copy.
            dataKey.fill(0)
        }

        EncryptedCredentialPayload(
            encryptedSecret = Base64.getEncoder().encodeToString(ciphertext),
            encryptedDek = Base64.getEncoder().encodeToString(wrappedDek),
            algorithm = ALGORITHM_ID,
            workerCertificateFingerprint = certificateService.computeCertificateFingerprint(certificate),
        )
    }

    private companion object {
        /** Algorithm identifier stored with every secret row. */
        const val ALGORITHM_ID = "RSA-OAEP-256+A256GCM"

        /** Key algorithm of the worker identity certificate. */
        const val RSA_ALGORITHM = "RSA"

        /** Data-key algorithm. */
        const val AES_ALGORITHM = "AES"

        /** Data-key size in bytes. */
        const val AES_KEY_BYTES = 32

        /** GCM nonce size in bytes. */
        const val GCM_NONCE_BYTES = 12

        /** GCM authentication tag size in bits. */
        const val GCM_TAG_BITS = 128

        /** AES-GCM transformation used for the value. */
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"

        /** RSA key-wrap transformation used for the data key. */
        const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** Explicit OAEP parameters shared with the worker. */
        val OAEP_PARAMETERS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
    }
}
