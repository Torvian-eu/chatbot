package eu.torvian.chatbot.server.service.security

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
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import java.util.Base64.getDecoder

/**
 * Default implementation of [CertificateService] using the Bouncy Castle cryptographic provider.
 *
 * This class handles certificate creation, conversion, and verification,
 * with support for both CA-signed and self-signed certificates.
 *
 * The Bouncy Castle provider is registered on first use.
 */
class DefaultCertificateService : CertificateService {

    /**
     * Initializes the Bouncy Castle provider if it hasn't been initialized yet.
     *
     * This is done in a thread-safe manner to ensure that the provider is only
     * registered once, even in a multi-threaded environment.
     */
    companion object {
        @Volatile
        private var initialized = false
        private val lock = Any()

        init {
            synchronized(lock) {
                if (!initialized) {
                    if (Security.getProvider("BC") == null) {
                        Security.addProvider(BouncyCastleProvider()) // Register BC provider once
                    }
                    initialized = true
                }
            }
        }
    }

    override fun generateRSAKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(2048) // 2048-bit key size is standard
        return keyPairGenerator.generateKeyPair()
    }

    override fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        subjectDN: String,
        validityYears: Int,
        keyUsages: Int?
    ): X509Certificate {
        val now = Date()
        val expiry = Calendar.getInstance().apply { add(Calendar.YEAR, validityYears) }.time
        val serialNumber = BigInteger(128, SecureRandom()) // Random 128-bit serial number

        val subject = X500Name(subjectDN)

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serialNumber, now, expiry, subject, keyPair.public
        )

        // Add optional key usage extension (e.g., digitalSignature, keyCertSign)
        keyUsages?.let {
            certBuilder.addExtension(Extension.keyUsage, true, KeyUsage(it))
        }

        // Sign the certificate using the private key and SHA256WithRSA algorithm
        val contentSigner: ContentSigner = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(keyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)

        // Convert to standard X509Certificate
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)
    }

    override fun generateCASignedCertificate(
        clientKeyPair: KeyPair,
        subjectDN: String,
        caPrivateKey: PrivateKey,
        caCertificate: X509Certificate,
        validityYears: Int,
        keyUsages: Int?
    ): X509Certificate {
        val now = Date()
        val expiry = Calendar.getInstance().apply { add(Calendar.YEAR, validityYears) }.time
        val serialNumber = BigInteger(128, SecureRandom())

        val issuer = X500Name(caCertificate.subjectX500Principal.name)
        val subject = X500Name(subjectDN)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serialNumber, now, expiry, subject, clientKeyPair.public
        )

        // Add optional key usage extension
        keyUsages?.let {
            certBuilder.addExtension(Extension.keyUsage, true, KeyUsage(it))
        }

        // Sign with CA private key
        val contentSigner: ContentSigner = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(caPrivateKey)

        val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)

        return JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)
    }

    override fun certificateToPem(cert: X509Certificate): String {
        return StringWriter().use { sw ->
            JcaPEMWriter(sw).use { it.writeObject(cert) } // Write as PEM
            sw.toString()
        }
    }

    override fun privateKeyToPem(key: PrivateKey): String {
        return StringWriter().use { sw ->
            JcaPEMWriter(sw).use { it.writeObject(key) } // Write as PEM
            sw.toString()
        }
    }

    override fun parseCertificate(pemCertificate: String): X509Certificate {
        return StringReader(pemCertificate).use { sr ->
            PEMParser(sr).use { parser ->
                val certHolder = parser.readObject() as? X509CertificateHolder
                    ?: throw IllegalArgumentException("Invalid certificate format")
                // Convert parsed holder to X509Certificate
                JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)
            }
        }
    }

    override fun parsePrivateKey(pemPrivateKey: String): PrivateKey {
        StringReader(pemPrivateKey).use { sr ->
            PEMParser(sr).use { parser ->
                val parsed = parser.readObject()
                val converter = org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider("BC")
                return when (parsed) {
                    is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                    is PEMKeyPair -> converter.getKeyPair(parsed).private
                    else -> throw IllegalArgumentException("Invalid private key format: ${parsed?.javaClass?.name}")
                }
            }
        }
    }

    override fun computeCertificateFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Convert binary hash to hex string
        return digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }

    override fun verifySignature(expectedMessage: String, signatureBase64: String, certificate: X509Certificate): Boolean {
        return try {
            val signatureBytes = getDecoder().decode(signatureBase64)
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(certificate.publicKey)
            signature.update(expectedMessage.toByteArray())
            signature.verify(signatureBytes) // true if valid
        } catch (_: Exception) {
            false // Catch any issue and treat as invalid signature
        }
    }

    override fun isCertificateIssuedBy(clientCert: X509Certificate, caCert: X509Certificate): Boolean {
        try {
            clientCert.verify(caCert.publicKey) // Cryptographically verify
        } catch (_: Exception) {
            return false
        }

        // Match issuer and subject attributes as well (e.g., CN, O)
        val clientIssuer = clientCert.issuerX500Principal
        val caSubject = caCert.subjectX500Principal

        return parseX500Attributes(clientIssuer.name) == parseX500Attributes(caSubject.name)
    }

    override fun isSubjectAttributePresent(
        certificate: X509Certificate,
        attribute: String,
        expectedValue: String
    ): Boolean {
        val attributes = parseX500Attributes(certificate.subjectX500Principal.name)
        return attributes[attribute] == expectedValue
    }

    /**
     * Parses an X.500 distinguished name (DN) string into a key-value map.
     *
     * For example: "CN=test,O=Company" → mapOf("CN" to "test", "O" to "Company")
     */
    private fun parseX500Attributes(dn: String): Map<String, String> {
        return dn.split(",").mapNotNull {
            val parts = it.trim().split("=")
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
    }
}

