package eu.torvian.chatbot.app.service.security

import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory

/**
 * Custom X509TrustManager implementing certificate pinning for JVM targets.
 *
 * Behavior summary:
 * - On first connection (no stored certificate) the manager will prompt the user via
 *   [CertificateTrustService] and optionally persist the certificate in [CertificateStorage].
 * - If a stored fingerprint matches the presented certificate the connection is allowed.
 * - If the presented certificate's fingerprint differs, the user is prompted to accept
 *   or reject the change; rejection aborts the TLS handshake with a [CertificateException].
 *
 * Storage access errors other than `NotFound` are treated as hard failures and will
 * abort the connection (to avoid insecure fallback behavior).
 *
 * Note: This class uses `runBlocking` to bridge the synchronous TrustManager interface
 * with suspendable storage and UI operations. Keep prompts brief to avoid long blocking
 * of network threads.
 *
 * @param certificateStorage Platform-specific persistent storage for trusted certificates.
 * @param serverUrl The server URL (used as storage key and logging context).
 * @param trustService Mediator service to post UI trust requests and await responses.
 */
@Suppress("CustomX509TrustManager")
class CustomTrustManager(
    private val certificateStorage: CertificateStorage,
    private val serverUrl: String,
    private val trustService: CertificateTrustService
) : X509TrustManager {

    private val logger = createKmpLogger("CustomTrustManager")
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

    // Get an instance of the system's default TrustManager ---
    private val defaultTrustManager: X509TrustManager = run {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        // Passing null initializes the factory with the default system keystore
        trustManagerFactory.init(null as KeyStore?)
        trustManagerFactory.trustManagers
            .first { it is X509TrustManager } as X509TrustManager
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            // 1. First, try to validate the chain using the system's default TrustManager.
            // If this succeeds, the certificate is trusted by the OS (e.g., signed by Let's Encrypt).
            // We don't need our pinning logic.
            logger.debug("Attempting validation with default system TrustManager.")
            defaultTrustManager.checkServerTrusted(chain, authType)
            logger.info("Certificate for $serverUrl is trusted by the system's default CAs.")

        } catch (e: CertificateException) {
            // 2. The system DOES NOT trust this certificate. This is the expected path for a self-signed cert.
            // Now, we fall back to our custom pinning logic.
            logger.warn("Default TrustManager rejected certificate. Falling back to custom pinning logic. Reason: ${e.message}")

            if (chain.isNullOrEmpty()) {
                // Re-throw the original exception if chain is invalid
                throw e
            }

            val serverCert = chain[0]
            val presentedFingerprint = computeSha256Fingerprint(serverCert)

            val storedCertResult = runBlocking { certificateStorage.getCertificate(serverUrl) }

            storedCertResult.fold(
                ifLeft = { error ->
                    when (error) {
                        is CertificateStorageError.NotFound -> {
                            handleFirstConnection(serverCert, presentedFingerprint)
                        }
                        else -> {
                            val msg = "Security error: Could not access certificate storage for $serverUrl. Aborting connection."
                            logger.error("$msg: $error", error.cause)
                            // Throw a new exception wrapping the storage error
                            throw CertificateException(msg, error.cause)
                        }
                    }
                },
                ifRight = { storedCertInfo ->
                    val storedFingerprint = storedCertInfo.second
                    if (presentedFingerprint == storedFingerprint) {
                        // Success! Pinned certificate matches.
                        logger.info("Certificate for $serverUrl matches stored fingerprint. Trust established.")
                        return
                    } else {
                        logger.warn("Stored certificate for $serverUrl does not match presented certificate. Prompting user.")
                        handleChangedCertificate(serverCert, presentedFingerprint, storedFingerprint)
                    }
                }
            )
        }
    }

    private fun handleFirstConnection(serverCert: X509Certificate, presentedFingerprint: String) {
        val details = getCertificateDetails(serverCert, presentedFingerprint)
        if (promptUserForTrust(details)) {
            // User accepted. Store the certificate.
            runBlocking {
                certificateStorage.storeCertificate(serverUrl, certificateToPem(serverCert), presentedFingerprint)
                    .onLeft { err ->
                        val msg = "Failed to store new certificate after user approval for $serverUrl: $err"
                        logger.error(msg, err.cause)
                        throw CertificateException(msg, err.cause) // Re-throw if storage fails
                    }
            }
        } else {
            // User rejected.
            throw CertificateException("Server certificate rejected by user for $serverUrl.")
        }
    }

    private fun handleChangedCertificate(serverCert: X509Certificate, presentedFingerprint: String, oldFingerprint: String) {
        val details = getCertificateDetails(serverCert, presentedFingerprint, oldFingerprint)
        if (promptUserForTrust(details)) {
            // User accepted the new certificate. Update the stored one.
            runBlocking {
                certificateStorage.storeCertificate(serverUrl, certificateToPem(serverCert), presentedFingerprint)
                    .onLeft { err ->
                        val msg = "Failed to update certificate after user approval for $serverUrl: $err"
                        logger.error(msg, err.cause)
                        throw CertificateException(msg, err.cause) // Re-throw if storage fails
                    }
            }
        } else {
            // User rejected the change.
            throw CertificateException("Server certificate has changed and was rejected by user for $serverUrl.")
        }
    }

    /**
     * Triggers the UI prompt via the trust service and blocks the current (network) thread
     * until the user responds.
     *
     * @param details Certificate details to show to the user.
     * @return true if the user accepted the certificate.
     */
    private fun promptUserForTrust(details: CertificateDetails): Boolean {
        // runBlocking is necessary here to bridge the synchronous X509TrustManager interface
        // with our suspendable CertificateTrustService function.
        return runBlocking {
            trustService.promptUserForTrust(details)
        }
    }

    @Suppress("TrustAllX509TrustManager")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Not used in our client implementation
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf() // Not relevant for custom pinning
    }

    // --- Helper Functions ---
    private fun getCertificateDetails(
        cert: X509Certificate,
        fingerprint: String,
        oldFingerprint: String? = null
    ): CertificateDetails {
        return CertificateDetails(
            fingerprint = fingerprint,
            oldFingerprint = oldFingerprint,
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            validUntil = dateFormat.format(cert.notAfter)
        )
    }

    private fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    private fun certificateToPem(cert: X509Certificate): String {
        val encoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
        val encodedCert = encoder.encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encodedCert\n-----END CERTIFICATE-----"
    }
}
