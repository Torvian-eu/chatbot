package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import kotlin.io.path.Path

/**
 * Loads worker runtime configuration from JSON.
 *
 * Path resolution precedence:
 * 1. Command-line argument `--config=<path>`
 * 2. Environment variable `CHATBOT_WORKER_CONFIG`
 * 3. Default `./worker-config/config.json`
 */
object WorkerConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private const val CONFIG_ENV = "CHATBOT_WORKER_CONFIG"
    private const val DEFAULT_CONFIG_PATH = "./worker-config/config.json"

    /**
     * Resolves the worker config file path based on CLI/env/default precedence.
     *
     * @param configPathOverride Optional config path provided from CLI parsing.
     * @param envProvider Environment lookup abstraction for testability.
     * @return Resolved config file path to load.
     */
    fun resolveConfigPath(
        configPathOverride: String? = null,
        envProvider: (String) -> String? = { key -> System.getenv(key) }
    ): Path {
        val envPath = envProvider(CONFIG_ENV)
        return Path(configPathOverride ?: envPath ?: DEFAULT_CONFIG_PATH)
    }

    /**
     * Loads and validates worker runtime configuration from the given file path.
     *
     * Validation ensures required string fields are not blank and `refreshSkewSeconds` is non-negative.
     *
     * @param path Path of the JSON config file.
     * @return Either a logical [WorkerConfigError] or a validated [WorkerRuntimeConfig].
     */
    fun load(path: Path): Either<WorkerConfigError, WorkerRuntimeConfig> = either {
        ensure(Files.exists(path)) { WorkerConfigError.ConfigMissing(path.toString()) }

        val raw = try {
            Files.readString(path)
        } catch (e: Exception) {
            raise(WorkerConfigError.ConfigReadFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()))
        }

        val config = try {
            json.decodeFromString<WorkerRuntimeConfig>(raw)
        } catch (e: SerializationException) {
            raise(WorkerConfigError.ConfigInvalid("Failed to parse JSON at $path: ${e.message}"))
        }

        validateServerBaseUrl(config.serverBaseUrl)?.let { raise(WorkerConfigError.ConfigInvalid(it)) }
        ensure(config.workerId > 0) { WorkerConfigError.ConfigInvalid("workerId must be > 0") }
        ensure(config.certificateFingerprint.isNotBlank()) { WorkerConfigError.ConfigInvalid("certificateFingerprint must not be blank") }
        ensure(config.privateKeyPemPath.isNotBlank()) { WorkerConfigError.ConfigInvalid("privateKeyPemPath must not be blank") }
        ensure(config.tokenFilePath.isNotBlank()) { WorkerConfigError.ConfigInvalid("tokenFilePath must not be blank") }
        ensure(config.refreshSkewSeconds >= 0) { WorkerConfigError.ConfigInvalid("refreshSkewSeconds must be >= 0") }

        config
    }

    private fun validateServerBaseUrl(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.isBlank()) {
            return "serverBaseUrl must not be blank"
        }

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return "serverBaseUrl must be a valid absolute HTTP(S) URL"
        }

        if (!uri.isAbsolute || uri.host.isNullOrBlank()) {
            return "serverBaseUrl must be an absolute HTTP(S) URL"
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "serverBaseUrl scheme must be http or https"
        }

        return null
    }
}

