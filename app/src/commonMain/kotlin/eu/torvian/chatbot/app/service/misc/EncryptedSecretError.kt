package eu.torvian.chatbot.app.service.misc

/**
 * Errors that can occur when encrypting and storing a secret.
 */
sealed class EncryptAndStoreError {
    /**
     * Encryption of the plaintext failed.
     *
     * @property plainTextLength The length of the plaintext that failed to encrypt
     * @property reason The reason encryption failed
     */
    data class EncryptionFailed(
        val plainTextLength: Int,
        val reason: String
    ) : EncryptAndStoreError()
}

/**
 * Errors that can occur when retrieving and decrypting a secret.
 */
sealed class RetrieveAndDecryptError {
    /**
     * Secret with the given ID was not found in the database.
     *
     * @property secretId The ID of the secret that was not found
     */
    data class SecretNotFound(val secretId: Long) : RetrieveAndDecryptError()

    /**
     * Decryption of the stored secret failed.
     *
     * @property secretId The ID of the secret that failed to decrypt
     * @property keyVersion The key version that was used to encrypt the secret
     * @property reason The reason decryption failed
     */
    data class DecryptionFailed(
        val secretId: Long,
        val keyVersion: Int,
        val reason: String
    ) : RetrieveAndDecryptError()
}

/**
 * Errors that can occur when updating a secret.
 */
sealed class UpdateSecretError {
    /**
     * Secret with the given ID was not found in the database.
     *
     * @property secretId The ID of the secret that was not found
     */
    data class SecretNotFound(val secretId: Long) : UpdateSecretError()

    /**
     * Encryption of the new plaintext failed.
     *
     * @property secretId The ID of the secret being updated
     * @property newPlainTextLength The length of the new plaintext that failed to encrypt
     * @property reason The reason encryption failed
     */
    data class EncryptionFailed(
        val secretId: Long,
        val newPlainTextLength: Int,
        val reason: String
    ) : UpdateSecretError()
}

/**
 * Errors that can occur when deleting a secret.
 */
sealed class DeleteSecretError {
    /**
     * Secret with the given ID was not found in the database.
     *
     * @property secretId The ID of the secret that was not found
     */
    data class SecretNotFound(val secretId: Long) : DeleteSecretError()

    /**
     * The secret is still referenced by other records and cannot be deleted.
     *
     * @property secretId The ID of the secret that is still in use
     * @property errorMessage The error message from the database
     */
    data class SecretInUse(
        val secretId: Long,
        val errorMessage: String
    ) : DeleteSecretError()
}