package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/**
 * Generates and validates worker setup secrets.
 */
class WorkerCertificateService {

    /**
     * Generates a new self-signed certificate and matching private key.
     *
     * @return Either a logical setup error or freshly generated worker secrets.
     */
    fun generateSecrets(): Either<WorkerSetupError, WorkerSecrets> {
        return try {
            val keyPair = generateRsaKeyPair()
            val certificate = generateSelfSignedCertificate(keyPair, DEFAULT_SUBJECT_DN)
            WorkerSecrets(
                certificatePem = certificateToPem(certificate),
                privateKeyPem = privateKeyToPem(keyPair.private),
                certificateFingerprint = computeCertificateFingerprint(certificate)
            ).right()
        } catch (e: Exception) {
            WorkerSetupError.CertificateGenerationFailed(e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    /**
     * Validates an existing secrets payload by checking PEM parseability, fingerprint consistency,
     * and that the private key actually matches the certificate.
     *
     * @param secrets Worker secrets candidate.
     * @param path Source file path used in error details.
     * @return Either a logical validation error or the validated secrets.
     */
    fun validateSecrets(secrets: WorkerSecrets, path: String): Either<WorkerSetupError, WorkerSecrets> {
        return try {
            val certificate = parseCertificate(secrets.certificatePem)
            val privateKey = parsePrivateKey(secrets.privateKeyPem)
            val expectedFingerprint = computeCertificateFingerprint(certificate)

            if (secrets.certificateFingerprint != expectedFingerprint) {
                return WorkerSetupError.SecretsInvalid(path, "certificate fingerprint does not match certificate PEM").left()
            }

            if (!privateKey.algorithm.equals("RSA", ignoreCase = true)) {
                return WorkerSetupError.SecretsInvalid(path, "private key algorithm must be RSA").left()
            }

            val validationMessage = "worker-setup-validation"
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(validationMessage.toByteArray(Charsets.UTF_8))
            val signedPayload = signature.sign()

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(certificate.publicKey)
            verifier.update(validationMessage.toByteArray(Charsets.UTF_8))
            if (!verifier.verify(signedPayload)) {
                return WorkerSetupError.SecretsInvalid(path, "certificate public key does not match private key").left()
            }

            secrets.right()
        } catch (e: Exception) {
            WorkerSetupError.SecretsInvalid(path, e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    /**
     * Generates the RSA key pair used to build the worker's setup certificate material.
     *
     * @return Newly generated RSA key pair.
     */
    private fun generateRsaKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Builds a self-signed X.509 certificate for the worker setup identity.
     *
     * @param keyPair Key pair used as certificate subject/public key and signer/private key.
     * @param subjectDn X.500 subject distinguished name for the generated certificate.
     * @param validityYears Number of years the certificate remains valid.
     * @param keyUsages X.509 key-usage bitmask to embed in the certificate extension.
     * @return Generated self-signed X.509 certificate.
     */
    private fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        subjectDn: String,
        validityYears: Int = 10,
        keyUsages: Int = KeyUsage.digitalSignature or KeyUsage.keyEncipherment
    ): X509Certificate {
        val now = Date()
        val expiry = Calendar.getInstance().apply { add(Calendar.YEAR, validityYears) }.time
        val serialNumber = BigInteger(128, SecureRandom())
        val subject = X500Name(subjectDn)

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            now,
            expiry,
            subject,
            keyPair.public
        )
        certBuilder.addExtension(Extension.keyUsage, true, KeyUsage(keyUsages))

        val contentSigner: ContentSigner = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(keyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)
    }

    /**
     * Serializes an X.509 certificate to PEM text.
     *
     * @param cert Certificate to serialize.
     * @return PEM-encoded certificate string.
     */
    private fun certificateToPem(cert: X509Certificate): String {
        return StringWriter().use { writer ->
            JcaPEMWriter(writer).use { it.writeObject(cert) }
            writer.toString()
        }
    }

    /**
     * Serializes a private key to PEM text.
     *
     * @param key Private key to serialize.
     * @return PEM-encoded private-key string.
     */
    private fun privateKeyToPem(key: PrivateKey): String {
        return StringWriter().use { writer ->
            JcaPEMWriter(writer).use { it.writeObject(key) }
            writer.toString()
        }
    }

    /**
     * Parses PEM-encoded certificate text into an X.509 certificate.
     *
     * @param pemCertificate PEM-encoded certificate text.
     * @return Parsed X.509 certificate instance.
     */
    private fun parseCertificate(pemCertificate: String): X509Certificate {
        return StringReader(pemCertificate).use { reader ->
            PEMParser(reader).use { parser ->
                val certHolder = parser.readObject() as? X509CertificateHolder
                    ?: throw IllegalArgumentException("Invalid certificate format")
                JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)
            }
        }
    }

    /**
     * Parses PEM-encoded private-key text into a `PrivateKey` instance.
     *
     * @param pemPrivateKey PEM-encoded private-key text.
     * @return Parsed private key instance.
     */
    private fun parsePrivateKey(pemPrivateKey: String): PrivateKey {
        return StringReader(pemPrivateKey).use { reader ->
            PEMParser(reader).use { parser ->
                val parsed = parser.readObject()
                val converter = JcaPEMKeyConverter().setProvider("BC")
                when (parsed) {
                    is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                    is PEMKeyPair -> converter.getKeyPair(parsed).private
                    else -> throw IllegalArgumentException("Invalid private key format: ${parsed?.javaClass?.name}")
                }
            }
        }
    }

    /**
     * Computes the SHA-256 fingerprint of the certificate bytes.
     *
     * @param cert Certificate to fingerprint.
     * @return Lowercase hexadecimal SHA-256 fingerprint.
     */
    private fun computeCertificateFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Default certificate subject used for worker self-signed identity generation.
         */
        private const val DEFAULT_SUBJECT_DN = "CN=Chatbot Worker, O=Chatbot, OU=Worker"

        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}


