package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import java.nio.file.Path

/**
 * Contract for reading and writing the worker secrets file.
 */
interface SecretsStore {
    /**
     * Reads [Secrets] from the given path.
     *
     * @param path Absolute or relative path to the secrets JSON file.
     * @return Either a [SecretsStoreError] or the parsed [Secrets].
     */
    suspend fun read(path: Path): Either<SecretsStoreError, Secrets>

    /**
     * Writes [Secrets] to the given path atomically when possible.
     *
     * @param path Absolute or relative path to the secrets JSON file.
     * @param secrets Secrets payload to persist.
     * @return Either a [SecretsStoreError] or [Unit] on success.
     */
    suspend fun write(path: Path, secrets: Secrets): Either<SecretsStoreError, Unit>
}
