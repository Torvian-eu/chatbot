package eu.torvian.chatbot.server.service.core.error.worker

/**
 * Logical errors for worker registration.
 */
sealed interface RegisterWorkerError {
    data class InvalidInput(val reason: String) : RegisterWorkerError
    data class InvalidCertificate(val reason: String) : RegisterWorkerError
    data class CertificateAlreadyRegistered(val fingerprint: String) : RegisterWorkerError
}

/**
 * Logical errors for worker challenge verification during service token flows.
 */
sealed interface AuthenticateWorkerError {
    data class WorkerNotFound(val workerId: Long) : AuthenticateWorkerError
    data class InvalidChallenge(val challengeId: String) : AuthenticateWorkerError
    data class InvalidSignature(val reason: String) : AuthenticateWorkerError
}


