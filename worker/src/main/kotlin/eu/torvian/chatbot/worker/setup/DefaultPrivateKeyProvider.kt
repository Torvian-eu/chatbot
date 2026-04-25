package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.worker.config.PathResolver
import eu.torvian.chatbot.worker.config.StorageConfig
import java.nio.file.Path

/**
 * Default [PrivateKeyProvider] that resolves the private key from environment or file.
 *
 * Precedence:
 * 1. Environment variable `CHATBOT_WORKER_PRIVATE_KEY_PEM` (trimmed; non-blank wins)
 * 2. `secrets.json` via [secretsStore]
 *
 * This keeps runtime secret acquisition separate from config loading and setup persistence.
 *
 * @property envProvider Environment variable lookup abstraction; defaults to [System.getenv].
 * @property pathResolver Resolves relative storage paths against the config directory.
 * @property secretsStore Reads persisted secrets from disk.
 */
class DefaultPrivateKeyProvider(
    private val envProvider: (String) -> String? = System::getenv,
    private val pathResolver: PathResolver = PathResolver(),
    private val secretsStore: SecretsStore = FileSecretsStore()
) : PrivateKeyProvider {

    override suspend fun loadPrivateKeyPem(
        configDir: Path,
        storage: StorageConfig
    ): Either<PrivateKeyLoadError, String> = either {
        val envValue = envProvider(PRIVATE_KEY_PEM_ENV_VAR)?.trim()
        if (!envValue.isNullOrEmpty()) {
            return@either envValue
        }

        val secretsPath = pathResolver.resolvePath(configDir, storage.secretsJsonPath)
        val secrets = secretsStore.read(secretsPath).mapLeft { error ->
            when (error) {
                is SecretsStoreError.NotFound ->
                    PrivateKeyLoadError.SecretsReadFailed(error.path, "file not found")
                is SecretsStoreError.ReadFailed ->
                    PrivateKeyLoadError.SecretsReadFailed(error.path, error.reason)
                is SecretsStoreError.Invalid ->
                    PrivateKeyLoadError.SecretsReadFailed(error.path, error.reason)
                is SecretsStoreError.WriteFailed ->
                    PrivateKeyLoadError.SecretsReadFailed(error.path, error.reason)
            }
        }.bind()

        secrets.privateKeyPem
    }

    companion object {
        /**
         * Fixed environment variable name for the worker private key PEM.
         *
         * Using a well-known name avoids extra indirection and keeps operator documentation simple.
         */
        const val PRIVATE_KEY_PEM_ENV_VAR = "CHATBOT_WORKER_PRIVATE_KEY_PEM"
    }
}
