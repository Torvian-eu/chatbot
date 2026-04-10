package eu.torvian.chatbot.server.data.dao.error

/**
 * Logical worker persistence errors emitted by the DAO layer.
 */
sealed interface WorkerError {
    /**
     * Raised when a worker identifier does not match any stored worker row.
     *
     * @property workerId Missing worker identifier.
     */
    data class NotFound(val workerId: Long) : WorkerError

    /**
     * Raised when a worker certificate fingerprint is already registered.
     *
     * @property fingerprint Conflicting SHA-256 certificate fingerprint.
     */
    data class DuplicateCertificateFingerprint(val fingerprint: String) : WorkerError

    /**
     * Raised when a worker challenge cannot be found, is expired, or was already consumed.
     *
     * @property challengeId Invalid or stale challenge identifier.
     */
    data class InvalidChallenge(val challengeId: String) : WorkerError
}

