package eu.torvian.chatbot.server.service.security

import java.security.KeyStore

/**
 * Interface for high-level certificate operations.
 */
interface CertificateManager {

    /**
     * Generates a self-signed certificate for local deployments.
     * The certificate and keystore will be saved to the configured path.
     */
    fun generateServerCertificate()

    /**
     * Loads an existing certificate from the keystore.
     *
     * @return The loaded KeyStore.
     * @throws IllegalArgumentException if the keystore file is not found.
     */
    fun loadCertificateFromKeystore(): KeyStore
}

