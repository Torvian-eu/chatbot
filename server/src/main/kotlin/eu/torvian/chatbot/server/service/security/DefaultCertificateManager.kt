package eu.torvian.chatbot.server.service.security

import eu.torvian.chatbot.server.domain.config.SslConfig
import io.ktor.network.tls.certificates.*
import java.io.File
import java.security.KeyStore

/**
 * Default implementation of [CertificateManager].
 * Uses Ktor's network-tls-certificates plugin for simple and readable self-signed certificate generation.
 */
class DefaultCertificateManager(
    private val sslConfig: SslConfig
) : CertificateManager {

    override fun generateServerCertificate() {
        val keystoreFile = File(sslConfig.keystorePath)
        if (keystoreFile.exists()) {
            return
        }

        val keyStore = buildKeyStore {
            certificate(sslConfig.keyAlias) {
                password = sslConfig.keyPassword
                domains = listOf("localhost", "127.0.0.1", "0.0.0.0")
            }
        }

        keystoreFile.parentFile?.mkdirs()
        keyStore.saveToFile(keystoreFile, sslConfig.keystorePassword)
    }

    override fun loadCertificateFromKeystore(): KeyStore {
        val keystoreFile = File(sslConfig.keystorePath)
        require(keystoreFile.exists()) { "Keystore file not found at ${sslConfig.keystorePath}" }

        return KeyStore.getInstance(keystoreFile, sslConfig.keystorePassword.toCharArray())
    }
}

