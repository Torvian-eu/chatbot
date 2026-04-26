package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.worker.config.AppConfigDto
import eu.torvian.chatbot.worker.config.AuthConfigDto
import eu.torvian.chatbot.worker.config.DefaultWorkerConfigLoader
import eu.torvian.chatbot.worker.config.IdentityConfigDto
import eu.torvian.chatbot.worker.config.PathResolver
import eu.torvian.chatbot.worker.config.RuntimeConfigDto
import eu.torvian.chatbot.worker.config.ServerConfigDto
import eu.torvian.chatbot.worker.config.SetupConfigDto
import eu.torvian.chatbot.worker.config.StorageConfigDto
import eu.torvian.chatbot.worker.config.WorkerConfigError
import eu.torvian.chatbot.worker.config.WorkerConfigLoader
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import java.util.UUID

/**
 * Default implementation of the worker setup flow.
 *
 * This manager coordinates initial worker provisioning by reading the merged worker config,
 * generating or validating identity material, registering the worker with the server,
 * and updating setup configuration files.
 *
 * @property configLoader Loader used to read and write layered config files.
 * @property secretsStore Store used to persist the private key in `secrets.json`.
 * @property certificateService Certificate helper used to generate and validate
 * worker identity material.
 * @property credentialProvider Source used to resolve setup-time user credentials.
 * @property displayNameProvider Source used to resolve the worker display name during setup.
 * @property setupApiFactory Factory that creates the server API client for a given server URL.
 */
class DefaultWorkerSetupManager(
    private val configLoader: WorkerConfigLoader = DefaultWorkerConfigLoader(),
    private val secretsStore: SecretsStore = FileSecretsStore(),
    private val certificateService: WorkerCertificateService = WorkerCertificateService(),
    private val credentialProvider: WorkerSetupCredentialProvider = DefaultWorkerSetupCredentialProvider(),
    private val displayNameProvider: WorkerSetupDisplayNameProvider = DefaultWorkerSetupDisplayNameProvider(),
    private val pathResolver: PathResolver = PathResolver(),
    private val setupApiFactory: (String) -> WorkerSetupApi = { serverUrl ->
        KtorWorkerSetupApi(createWorkerSetupHttpClient(serverUrl))
    }
) : WorkerSetupManager {

    /**
     * Executes the setup flow for a worker configuration directory.
     *
     * Generates or validates identity material, logs in to the server,
     * registers the worker, and persists updated configuration layers.
     *
     * @param configDir Target worker config directory path.
     * @param mergedConfig Merged worker configuration loaded by the caller.
     * @param serverUrlOverride Optional setup-time override for `worker.server.baseUrl`.
     * @return Either a logical setup error or `Unit` on successful setup.
     */
    override suspend fun run(
        configDir: Path,
        mergedConfig: AppConfigDto,
        serverUrlOverride: String?
    ): Either<WorkerSetupError, Unit> = either {
        val normalizedConfigDir = configDir.toAbsolutePath().normalize()
        logger.info("Worker setup started (configDir={})", normalizedConfigDir)

        val serverUrl = resolveServerUrl(serverUrlOverride, mergedConfig, normalizedConfigDir).bind()
        val uid = resolveWorkerUid(mergedConfig).bind()
        val defaultDisplayName = mergedConfig.worker?.identity?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: "my-worker"
        val displayName = displayNameProvider.resolveDisplayName(defaultDisplayName).bind()
        val storage = mergedConfig.worker?.storage
        val auth = mergedConfig.worker?.auth

        val secretsPathValue = storage?.secretsJsonPath?.takeIf { it.isNotBlank() } ?: DEFAULT_SECRETS_JSON_PATH
        val tokenPathValue = storage?.tokenFilePath?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_FILE_PATH

        val secretsPath = pathResolver.resolvePath(normalizedConfigDir, secretsPathValue)

        val preparedIdentity = loadOrGenerateIdentity(
            mergedConfig = mergedConfig,
            secretsPath = secretsPath
        ).bind()

        val credentials = credentialProvider.resolveCredentials().bind()
        val setupApi = setupApiFactory(serverUrl)

        var accessToken: String? = null
        var logoutError: WorkerSetupError? = null

        try {
            accessToken = setupApi.login(credentials.username, credentials.password).bind()

            setupApi.registerWorker(
                accessToken = accessToken,
                workerUid = uid,
                displayName = displayName,
                certificatePem = preparedIdentity.certificatePem
            ).bind()
        } finally {
            accessToken?.let { token ->
                setupApi.logout(token).onLeft { logoutError = it }
            }
            setupApi.close()
        }

        logoutError?.let { raise(it) }

        val updatedApplicationConfig = buildUpdatedApplicationConfig(
            existingConfig = mergedConfig,
            serverUrl = serverUrl,
            uid = uid,
            displayName = displayName,
            certificateFingerprint = preparedIdentity.certificateFingerprint,
            certificatePem = preparedIdentity.certificatePem,
            secretsPathValue = secretsPathValue,
            tokenPathValue = tokenPathValue,
            refreshSkewSeconds = auth?.refreshSkewSeconds ?: 60L
        )

        configLoader.saveLayerDto(
            normalizedConfigDir,
            DefaultWorkerConfigLoader.APPLICATION_FILE_NAME,
            updatedApplicationConfig
        ).mapLeft { mapConfigError(it, normalizedConfigDir) }.bind()

        configLoader.saveLayerDto(
            normalizedConfigDir,
            DefaultWorkerConfigLoader.SETUP_FILE_NAME,
            buildCompletedSetupConfig()
        ).mapLeft { mapConfigError(it, normalizedConfigDir) }.bind()
    }

    /**
     * Loads an existing identity from config and secrets, or generates a fresh one.
     *
     * Behavior:
     * - If the secrets store is absent ([SecretsStoreError.NotFound]), a new identity is generated.
     * - If the secrets store is unreadable or invalid, setup fails so the operator
     *   can diagnose the issue rather than silently overwriting existing material.
     * - If existing certificate/private-key material is present but does not validate
     *   as a matching identity, setup generates a fresh identity.
     *
     * @param mergedConfig Parsed merged worker config JSON.
     * @param secretsPath Absolute path to the secrets JSON file.
     * @return Either a logical setup error or the resolved/generated identity.
     */
    private suspend fun loadOrGenerateIdentity(
        mergedConfig: AppConfigDto,
        secretsPath: Path
    ): Either<WorkerSetupError, GeneratedIdentity> = either {
        val configuredIdentity = mergedConfig.worker?.identity
        val existingPrivateKey = when (val readResult = secretsStore.read(secretsPath)) {
            is Either.Left -> when (val error = readResult.value) {
                is SecretsStoreError.NotFound -> null
                is SecretsStoreError.ReadFailed -> raise(
                    WorkerSetupError.ConfigReadFailed(error.path, error.reason)
                )
                is SecretsStoreError.Invalid -> raise(
                    WorkerSetupError.SecretsInvalid(error.path, error.reason)
                )
                is SecretsStoreError.WriteFailed -> raise(
                    // Defensive: read() should not normally produce WriteFailed, but handle it explicitly.
                    WorkerSetupError.ConfigReadFailed(
                        error.path,
                        "Unexpected write error during read: ${error.reason}"
                    )
                )
            }
            is Either.Right -> readResult.value.privateKeyPem
        }

        val existingCertificatePem = configuredIdentity?.certificatePem?.takeIf { it.isNotBlank() }
        val existingFingerprint = configuredIdentity?.certificateFingerprint?.takeIf { it.isNotBlank() }

        if (existingPrivateKey != null && existingCertificatePem != null && existingFingerprint != null) {
            val validation = certificateService.validateIdentity(
                certificatePem = existingCertificatePem,
                certificateFingerprint = existingFingerprint,
                privateKeyPem = existingPrivateKey,
                path = secretsPath.toString()
            )
            if (validation.isRight()) {
                return@either GeneratedIdentity(
                    certificatePem = existingCertificatePem,
                    certificateFingerprint = existingFingerprint,
                    privateKeyPem = existingPrivateKey
                )
            }
        }

        val generated = certificateService.generateIdentity().bind()

        secretsStore.write(
            secretsPath,
            Secrets(privateKeyPem = generated.privateKeyPem)
        ).mapLeft { error ->
            when (error) {
                is SecretsStoreError.NotFound -> WorkerSetupError.FileWriteFailed(error.path, "Path not found")
                is SecretsStoreError.ReadFailed -> WorkerSetupError.FileWriteFailed(error.path, error.reason)
                is SecretsStoreError.Invalid -> WorkerSetupError.FileWriteFailed(error.path, error.reason)
                is SecretsStoreError.WriteFailed -> WorkerSetupError.FileWriteFailed(error.path, error.reason)
            }
        }.bind()

        generated
    }

    private fun resolveServerUrl(
        serverUrlOverride: String?,
        mergedConfig: AppConfigDto,
        configDir: Path
    ): Either<WorkerSetupError, String> {
        val override = serverUrlOverride?.trim()?.takeIf { it.isNotBlank() }
        if (override != null) return override.right()

        val configured = mergedConfig.worker?.server?.baseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (configured != null) return configured.right()

        return WorkerSetupError.ServerUrlMissing(configDir.toString()).left()
    }

    private fun resolveWorkerUid(mergedConfig: AppConfigDto): Either<WorkerSetupError, String> {
        val configured = mergedConfig.worker?.identity?.uid?.trim()?.takeIf { it.isNotBlank() }
        return configured?.right() ?: UUID.randomUUID().toString().right()
    }

    /**
     * Builds an updated application config DTO that patches only the fields owned by setup,
     * preserving any existing nested values that setup does not touch.
     *
     * @param existingConfig Previously merged application configuration.
     * @param serverUrl Resolved server base URL to persist.
     * @param uid Resolved or generated worker unique identifier.
     * @param certificateFingerprint Generated certificate fingerprint to persist.
     * @param certificatePem Generated public certificate PEM to persist.
     * @param secretsPathValue Path value for the secrets JSON file.
     * @param tokenPathValue Path value for the token cache file.
     * @param refreshSkewSeconds Token refresh skew in seconds.
     * @return Patched [AppConfigDto] ready to be written as `application.json`.
     */
    private fun buildUpdatedApplicationConfig(
        existingConfig: AppConfigDto,
        serverUrl: String,
        uid: String,
        displayName: String,
        certificateFingerprint: String,
        certificatePem: String,
        secretsPathValue: String,
        tokenPathValue: String,
        refreshSkewSeconds: Long
    ): AppConfigDto {
        val existingWorker = existingConfig.worker ?: RuntimeConfigDto()
        val existingServer = existingWorker.server ?: ServerConfigDto()
        val existingIdentity = existingWorker.identity ?: IdentityConfigDto()
        val existingStorage = existingWorker.storage ?: StorageConfigDto()
        val existingAuth = existingWorker.auth ?: AuthConfigDto()

        return existingConfig.copy(
            worker = existingWorker.copy(
                server = existingServer.copy(
                    baseUrl = serverUrl
                ),
                identity = existingIdentity.copy(
                    uid = uid,
                    displayName = displayName,
                    certificateFingerprint = certificateFingerprint,
                    certificatePem = certificatePem
                ),
                storage = existingStorage.copy(
                    secretsJsonPath = secretsPathValue,
                    tokenFilePath = tokenPathValue
                ),
                auth = existingAuth.copy(
                    refreshSkewSeconds = refreshSkewSeconds
                )
            ),
            setup = null
        )
    }

    private fun buildCompletedSetupConfig(): AppConfigDto {
        return AppConfigDto(
            setup = SetupConfigDto(required = false)
        )
    }



    /**
     * Maps a [WorkerConfigError] into the [WorkerSetupError] sealed hierarchy.
     *
     * @param error Config loader error to translate.
     * @param configDir Config directory used to build a contextual path for validation errors.
     * @return Semantically matching setup error.
     */
    private fun mapConfigError(error: WorkerConfigError, configDir: Path): WorkerSetupError {
        return when (error) {
            is WorkerConfigError.ConfigMissing -> WorkerSetupError.ConfigReadFailed(
                error.path,
                "Configuration file not found"
            )

            is WorkerConfigError.ConfigReadFailed -> WorkerSetupError.ConfigReadFailed(error.path, error.reason)
            is WorkerConfigError.ConfigInvalid -> WorkerSetupError.ConfigInvalid(
                configLoader.resolveLayerPath(configDir, DefaultWorkerConfigLoader.APPLICATION_FILE_NAME).toString(),
                error.description
            )
        }
    }

    companion object {
        private const val DEFAULT_SECRETS_JSON_PATH = "./secrets.json"
        private const val DEFAULT_TOKEN_FILE_PATH = "./token.json"
        private val logger: Logger = LogManager.getLogger(DefaultWorkerSetupManager::class.java)
    }
}



