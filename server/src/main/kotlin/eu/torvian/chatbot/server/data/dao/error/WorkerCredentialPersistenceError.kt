package eu.torvian.chatbot.server.data.dao.error

/**
 * Logical failure returned by credential persistence.
 *
 * All variants carry identifiers or operator-authored names, never a secret value.
 */
sealed interface WorkerCredentialPersistenceError {
    /**
     * The addressed row does not exist.
     *
     * @property id Row identifier that was requested.
     */
    data class NotFound(val id: Long) : WorkerCredentialPersistenceError

    /**
     * A profile with the same name already exists on the worker.
     *
     * @property workerId Worker the name must be unique on.
     * @property name Profile name that collided.
     */
    data class DuplicateProfileName(val workerId: Long, val name: String) : WorkerCredentialPersistenceError

    /**
     * A secret with the same reference already exists on the worker.
     *
     * @property workerId Worker the reference must be unique on.
     * @property reference Reference that collided.
     */
    data class DuplicateSecretReference(val workerId: Long, val reference: String) :
        WorkerCredentialPersistenceError
}
