package eu.torvian.chatbot.app.database.dao.error

/**
 * Error types for encrypted secret DAO operations.
 */

/** Error for retrieval operations. */
sealed class EncryptedSecretError {
    /** Indicates that an encrypted secret with the given id was not found. */
    data class NotFound(val id: Long) : EncryptedSecretError()
}

/** Error types for update operations. */
sealed class UpdateEncryptedSecretError {
    /** Indicates that the encrypted secret to update was not found. */
    data class NotFound(val id: Long) : UpdateEncryptedSecretError()
}

/** Error types for delete operations. */
sealed class DeleteEncryptedSecretError {
    /** Indicates that no secret was found for the given id. */
    data class NotFound(val id: Long) : DeleteEncryptedSecretError()

    /** Indicates the secret could not be deleted because it is referenced by another table. */
    data class ForeignKeyViolation(val message: String, val cause: Throwable) : DeleteEncryptedSecretError()
}

