package eu.torvian.chatbot.worker.credential

import arrow.core.Either

/**
 * Decrypts one worker-bound secret with the worker private key.
 *
 * Implementations must not cache plaintext: every `attach` decrypts again, and the caller closes the
 * returned value once the channel material has been built.
 */
internal interface CredentialSecretDecryptor {
    /**
     * Unwraps [secret] into its plaintext value.
     *
     * @param secret Ciphertext delivered by the server for this worker.
     * @return Either a typed resolution failure or the value the caller must close.
     */
    fun decrypt(secret: EncryptedWorkerSecret): Either<SecretResolutionError, SecretValue>
}
