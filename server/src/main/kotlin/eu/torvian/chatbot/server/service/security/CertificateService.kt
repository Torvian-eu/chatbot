package eu.torvian.chatbot.server.service.security

import java.security.*
import java.security.cert.X509Certificate

/**
 * Interface for performing certificate-related operations.
 *
 * This abstraction allows certificate generation, parsing, validation,
 * and signature verification using a pluggable implementation (e.g., Bouncy Castle).
 *
 * Designed to be injected for better testability and parallel test safety.
 */
interface CertificateService {

    /**
     * Generates a new RSA key pair (private and public keys).
     *
     * @return A KeyPair containing an RSA private and public key.
     */
    fun generateRSAKeyPair(): KeyPair

    /**
     * Generates a self-signed X.509 certificate (for use as a CA or standalone cert).
     *
     * @param keyPair The key pair used for signing.
     * @param subjectDN The distinguished name (DN) of the certificate.
     * @param validityYears The number of years the certificate should be valid.
     * @param keyUsages Optional key usage flags (e.g., certificate signing, digital signature).
     * @return A self-signed X.509 certificate.
     */
    fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        subjectDN: String,
        validityYears: Int = 10,
        keyUsages: Int? = null
    ): X509Certificate

    /**
     * Generates a client certificate signed by a CA.
     *
     * @param clientKeyPair The key pair for the client.
     * @param subjectDN The distinguished name (DN) of the client.
     * @param caPrivateKey The private key of the CA.
     * @param caCertificate The CA's X.509 certificate.
     * @param validityYears Number of years the client certificate is valid.
     * @param keyUsages Optional key usage flags (e.g., digital signature, key encipherment).
     * @return A CA-signed X.509 client certificate.
     */
    fun generateCASignedCertificate(
        clientKeyPair: KeyPair,
        subjectDN: String,
        caPrivateKey: PrivateKey,
        caCertificate: X509Certificate,
        validityYears: Int = 2,
        keyUsages: Int? = null
    ): X509Certificate

    /**
     * Converts an X.509 certificate to a PEM-encoded string.
     *
     * @param cert The X.509 certificate to be converted.
     * @return A PEM-formatted string representing the certificate.
     */
    fun certificateToPem(cert: X509Certificate): String

    /**
     * Converts a private key to a PEM-encoded string.
     *
     * @param key The private key to be converted.
     * @return A PEM-formatted string representing the private key.
     */
    fun privateKeyToPem(key: PrivateKey): String

    /**
     * Parses a PEM-encoded X.509 certificate from a string.
     *
     * @param pemCertificate A string containing the PEM-encoded certificate.
     * @return An X509Certificate instance parsed from the input string.
     * @throws IllegalArgumentException if parsing fails.
     */
    fun parseCertificate(pemCertificate: String): X509Certificate

    /**
     * Parses a PEM-encoded private key string into a PrivateKey object.
     *
     * @param pemPrivateKey The PEM-encoded private key string to be parsed.
     * @return The corresponding PrivateKey object.
     * @throws IllegalArgumentException if the provided key format is invalid.
     */
    fun parsePrivateKey(pemPrivateKey: String): PrivateKey

    /**
     * Computes the SHA-256 fingerprint of an X.509 certificate.
     *
     * @param cert The X.509 certificate to generate a fingerprint for.
     * @return A hexadecimal string representing the SHA-256 fingerprint of the certificate.
     */
    fun computeCertificateFingerprint(cert: X509Certificate): String

    /**
     * Verifies that a given digital signature is valid for a specific message using the public key
     * from the provided certificate.
     *
     * @param expectedMessage The original message that was signed.
     * @param signatureBase64 The Base64-encoded signature to verify.
     * @param certificate The X.509 certificate containing the public key for verification.
     * @return true if the signature is valid, false otherwise.
     */
    fun verifySignature(expectedMessage: String, signatureBase64: String, certificate: X509Certificate): Boolean

    /**
     * Determines if a client certificate was issued (signed) by a specific CA certificate.
     *
     * @param clientCert The client certificate to verify.
     * @param caCert The CA certificate that allegedly issued the client certificate.
     * @return true if the client certificate was signed by the CA, false otherwise.
     */
    fun isCertificateIssuedBy(clientCert: X509Certificate, caCert: X509Certificate): Boolean

    /**
     * Checks if a specific attribute (e.g., "CN", "O", "OU") in the certificate's subject DN
     * matches the expected value.
     *
     * @param certificate The X.509 certificate to check.
     * @param attribute The attribute name (e.g., "CN" for Common Name).
     * @param expectedValue The expected value for the attribute.
     * @return true if the attribute matches the expected value, false otherwise.
     */
    fun isSubjectAttributePresent(
        certificate: X509Certificate,
        attribute: String,
        expectedValue: String
    ): Boolean
}

