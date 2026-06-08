package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * JVM implementation of [AsymmetricCryptoProvider] using Ed25519.
 *
 * This implementation uses BouncyCastle to provide Ed25519 asymmetric cryptographic
 * operations for request signing. Ed25519 provides fast, secure signatures with
 * small key sizes and is well-suited for mobile and desktop applications.
 */
class JvmAsymmetricCryptoProvider : AsymmetricCryptoProvider {

    init {
        // Ensure BouncyCastle provider is available
        if (Security.getProvider("BC") == null) {
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    override suspend fun generateKeyPair(): Either<AsymmetricCryptoError, AsymmetricKeyPair> = either {
        catch({
            val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519", "BC")
            val keyPair = keyPairGenerator.generateKeyPair()

            // Encode keys to bytes using standard formats
            val publicKeyBytes = keyPair.public.encoded
            val privateKeyBytes = keyPair.private.encoded

            AsymmetricKeyPair(
                publicKey = publicKeyBytes,
                privateKey = privateKeyBytes
            )
        }) { e: Exception ->
            raise(AsymmetricCryptoError.KeyGenerationFailed(
                "Failed to generate Ed25519 key pair: ${e.message}",
                e
            ))
        }
    }

    override suspend fun sign(data: String, privateKey: ByteArray): Either<AsymmetricCryptoError, String> = either {
        catch({
            val keySpec = PKCS8EncodedKeySpec(privateKey)
            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
            val privatekey = keyFactory.generatePrivate(keySpec)

            val signature = Signature.getInstance("Ed25519", "BC")
            signature.initSign(privatekey)
            signature.update(data.toByteArray(Charsets.UTF_8))
            val signatureBytes = signature.sign()

            Base64.getEncoder().encodeToString(signatureBytes)
        }) { e: Exception ->
            raise(AsymmetricCryptoError.SignatureGenerationFailed(
                "Failed to sign data with Ed25519: ${e.message}",
                e
            ))
        }
    }

    override suspend fun verify(
        data: String,
        signature: String,
        publicKey: ByteArray
    ): Either<AsymmetricCryptoError, Boolean> = either {
        catch({
            val keySpec = X509EncodedKeySpec(publicKey)
            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
            val publickey = keyFactory.generatePublic(keySpec)

            val signatureBytes = Base64.getDecoder().decode(signature)

            val sig = Signature.getInstance("Ed25519", "BC")
            sig.initVerify(publickey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            sig.verify(signatureBytes)
        }) { e: Exception ->
            raise(AsymmetricCryptoError.SignatureVerificationFailed(
                "Failed to verify Ed25519 signature: ${e.message}",
                e
            ))
        }
    }
}
