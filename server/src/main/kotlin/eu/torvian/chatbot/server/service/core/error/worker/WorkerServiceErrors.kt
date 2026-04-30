package eu.torvian.chatbot.server.service.core.error.worker

/**
 * Logical errors for worker registration.
 */
sealed interface RegisterWorkerError {
    data class InvalidInput(val reason: String) : RegisterWorkerError
    data class InvalidCertificate(val reason: String) : RegisterWorkerError
    data class CertificateAlreadyRegistered(val fingerprint: String) : RegisterWorkerError
    data class WorkerUidAlreadyRegistered(val workerUid: String) : RegisterWorkerError
}

/**
 * Logical errors for worker challenge verification during service token flows.
 */
sealed interface AuthenticateWorkerError {
    data class WorkerNotFound(val workerUid: String) : AuthenticateWorkerError
    data class InvalidChallenge(val challengeId: String) : AuthenticateWorkerError
    data class InvalidSignature(val reason: String) : AuthenticateWorkerError
}

/**
 * Logical errors for worker update operations.
 */
sealed interface UpdateWorkerError {
    /**
     * The worker was not found.
     *
     * @property workerId Missing worker identifier.
     */
    data class NotFound(val workerId: Long) : UpdateWorkerError

    /**
     * The authenticated user does not own this worker.
     *
     * @property workerId Target worker identifier.
     * @property ownerUserId Actual owner of the worker.
     */
    data class Forbidden(val workerId: Long, val ownerUserId: Long) : UpdateWorkerError
}

/**
 * Logical errors for worker delete operations.
 */
sealed interface DeleteWorkerError {
    /**
     * The worker was not found.
     *
     * @property workerId Missing worker identifier.
     */
    data class NotFound(val workerId: Long) : DeleteWorkerError

    /**
     * The authenticated user does not own this worker.
     *
     * @property workerId Target worker identifier.
     * @property ownerUserId Actual owner of the worker.
     */
    data class Forbidden(val workerId: Long, val ownerUserId: Long) : DeleteWorkerError
}
