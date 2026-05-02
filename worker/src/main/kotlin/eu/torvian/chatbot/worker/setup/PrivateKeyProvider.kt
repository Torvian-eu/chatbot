package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import eu.torvian.chatbot.worker.config.StorageConfig
import eu.torvian.chatbot.worker.main.WorkerMain
import java.nio.file.Path

/**
 * Abstraction for loading the worker private key PEM at runtime.
 *
 * Implementations may source the key from environment variables, files, keychains,
 * or other secret stores. The caller (for example [WorkerMain]) should not care about
 * the transport mechanism.
 */
interface PrivateKeyProvider {

    /**
     * Loads the PEM-encoded private key.
     *
     * Resolution precedence is implementation-defined. The default implementation
     * ([DefaultPrivateKeyProvider]) checks these sources in order:
     * 1. Environment variable `CHATBOT_WORKER_PRIVATE_KEY_PEM`
     * 2. `secrets.json` file referenced by [storage]
     *
     * @param configDir Worker configuration directory used to resolve relative paths.
     * @param storage Storage configuration containing the secrets file path.
     * @return Either a [PrivateKeyLoadError] or the PEM-encoded private key text.
     */
    suspend fun loadPrivateKeyPem(
        configDir: Path,
        storage: StorageConfig
    ): Either<PrivateKeyLoadError, String>
}
