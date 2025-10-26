package eu.torvian.chatbot.app.service.security

import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import arrow.core.Either
import arrow.core.left
import arrow.core.right

private val logger = createKmpLogger("BrowserCertificateStorage")

/**
 * No-Op Implementation: Certificate pinning is not applicable in the browser.
 * The browser's native networking stack enforces certificate validation and pinning
 * decisions, so attempting to replicate that behaviour in WASM/JS is neither possible
 * nor recommended.
 *
 * This implementation keeps the same API surface as the JVM storage but always
 * returns safe defaults (e.g. `NotFound` for `getCertificate`). It exists so the
 * higher-level code can remain platform-agnostic.
 */
class BrowserCertificateStorage : CertificateStorage {
    init {
        logger.warn("BrowserCertificateStorage is a no-op implementation as certificate pinning is not applicable in the browser.")
    }

    /**
     * No-op store operation. Always succeeds on the right side.
     */
    override suspend fun storeCertificate(serverUrl: String, certificatePem: String, fingerprint: String): Either<CertificateStorageError, Unit> = Unit.right()

    /**
     * Browsers handle certificate validation; return `NotFound` so calling code treats
     * it as first-connection behaviour if applicable.
     */
    override suspend fun getCertificate(serverUrl: String): Either<CertificateStorageError, Pair<String, String>> = CertificateStorageError.NotFound().left() // Always "not found"

    /**
     * No-op remove operation.
     */
    override suspend fun removeCertificate(serverUrl: String): Either<CertificateStorageError, Unit> = Unit.right()

    /**
     * Always reports false because no certificate is persisted in the browser.
     */
    override suspend fun hasCertificate(serverUrl: String): Either<CertificateStorageError, Boolean> = false.right()
}
