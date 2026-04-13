package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.worker.config.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.*
import java.util.*

/**
 * Default implementation of the worker setup flow.
 *
 * This manager coordinates initial worker provisioning by reading the merged worker config,
 * generating or validating `secrets.json`, registering the worker with the server,
 * and updating setup configuration files.
 *
 * @property certificateService Certificate helper used to generate and validate
 * worker identity material.
 * @property credentialProvider Source used to resolve setup-time user credentials.
 * @property setupApiFactory Factory that creates the server API client for a given server URL.
 */
class DefaultWorkerSetupManager(
    private val configLoader: WorkerConfigLoader = DefaultWorkerConfigLoader(),
    private val certificateService: WorkerCertificateService = WorkerCertificateService(),
    private val credentialProvider: WorkerSetupCredentialProvider = DefaultWorkerSetupCredentialProvider(),
    private val setupApiFactory: (String) -> WorkerSetupApi = { serverUrl ->
        KtorWorkerSetupApi(createWorkerSetupHttpClient(serverUrl))
    }
) : WorkerSetupManager {
    /**
     * JSON configuration shared by setup file read/write operations.
     */
    private val json = Json {
        allowComments = true
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Creates or updates the worker setup files.
     *
     * The setup flow performs the following steps:
     * 1. Use the merged worker config provided by the caller.
     * 2. Resolve setup credentials (environment variables or interactive prompt).
     * 3. Login to the server.
     * 4. Generate or reuse a valid `secrets.json` file containing the certificate and private key.
     * 5. Register the worker certificate and logout.
     * 6. Persist the resolved server URL, fingerprint, and path settings to `application.json`.
     * 7. Persist `setup.json` with `setup.required = false` so startup skips setup next time.
     *
     * @param configDir Target worker config directory path.
     * @param mergedConfig Merged worker configuration loaded by the caller.
     * @param serverUrlOverride Optional setup-time CLI override for `worker.serverBaseUrl`.
     * @return Either a logical setup error or `Unit` on successful setup.
     */
    override suspend fun run(
        configDir: Path,
        mergedConfig: WorkerAppConfigDto,
        serverUrlOverride: String?
    ): Either<WorkerSetupError, Unit> =
        either {
            val normalizedConfigDir = configDir.toAbsolutePath().normalize()
            logger.info("Worker setup started (configDir={})", normalizedConfigDir)

            val serverUrl = resolveServerUrl(serverUrlOverride, mergedConfig, normalizedConfigDir).bind()
            val workerUid = resolveWorkerUid(mergedConfig).bind()
            val secretsPathValue = mergedConfig.worker?.secretsJsonPath?.takeIf { it.isNotBlank() }
                ?: DEFAULT_SECRETS_JSON_PATH
            val tokenPathValue = mergedConfig.worker?.tokenFilePath?.takeIf { it.isNotBlank() }
                ?: DEFAULT_TOKEN_FILE_PATH
            logger.info("Resolved setup target (serverUrl={}, workerUid={})", serverUrl, workerUid)
            logger.debug(
                "Resolved setup paths (secretsJsonPath={}, tokenFilePath={})",
                secretsPathValue,
                tokenPathValue
            )

            val secretsPath = resolvePath(normalizedConfigDir, secretsPathValue)
            val secrets = loadOrGenerateSecrets(secretsPath).bind()
            logger.info(
                "Worker identity material ready (secretsPath={}, fingerprintSuffix={})",
                secretsPath,
                secrets.certificateFingerprint.takeLast(8)
            )

            val credentials = credentialProvider.resolveCredentials().bind()
            logger.info("Setup credentials resolved for worker registration")

            val setupApi = setupApiFactory(serverUrl)
            var accessToken: String? = null
            var logoutError: WorkerSetupError? = null

            try {
                logger.info("Logging in to server for setup registration")
                accessToken = setupApi.login(credentials.username, credentials.password).bind()
                logger.info("Login successful; registering worker {}", workerUid)

                setupApi.registerWorker(
                    accessToken = accessToken,
                    workerUid = workerUid,
                    certificatePem = secrets.certificatePem
                ).bind()
                logger.info("Worker registration completed for {}", workerUid)

            } finally {
                accessToken?.let { token ->
                    logger.debug("Attempting setup logout")
                    setupApi.logout(token).onLeft { error ->
                        logoutError = error
                        logger.warn("Setup logout failed: {}", error)
                    }
                }
                setupApi.close()
                logger.debug("Setup API client closed")
            }

            logoutError?.let { error ->
                logger.error("Worker setup failed during logout phase: {}", error)
                raise(error)
            }

            val updatedApplicationConfig = buildUpdatedApplicationConfig(
                existingConfig = mergedConfig,
                serverUrl = serverUrl,
                workerUid = workerUid,
                secretsPathValue = secretsPathValue,
                tokenPathValue = tokenPathValue,
                fingerprint = secrets.certificateFingerprint
            )
            configLoader.saveLayerDto(
                normalizedConfigDir,
                DefaultWorkerConfigLoader.APPLICATION_FILE_NAME,
                updatedApplicationConfig
            ).mapLeft { mapConfigError(it, normalizedConfigDir) }
                .onLeft { logger.error("Failed to persist application setup config: {}", it) }
                .bind()
            logger.info("Persisted worker application setup config")

            val completedSetupConfig = buildCompletedSetupConfig()
            configLoader.saveLayerDto(
                normalizedConfigDir,
                DefaultWorkerConfigLoader.SETUP_FILE_NAME,
                completedSetupConfig
            ).mapLeft { mapConfigError(it, normalizedConfigDir) }
                .onLeft { logger.error("Failed to persist setup completion marker: {}", it) }
                .bind()
            logger.info("Worker setup completed successfully")
        }

    /**
     * Resolves the server URL for setup using CLI override first and then merged config.
     *
     * @param serverUrlOverride Optional CLI-provided server URL.
     * @param mergedConfig Parsed merged worker config JSON.
     * @param configDir Config directory used for missing-value error context.
     * @return Either a logical setup error or the resolved server URL.
     */
    private fun resolveServerUrl(
        serverUrlOverride: String?,
        mergedConfig: WorkerAppConfigDto,
        configDir: Path
    ): Either<WorkerSetupError, String> {
        val override = serverUrlOverride?.trim()?.takeIf { it.isNotBlank() }
        if (override != null) {
            return override.right()
        }

        val configured = mergedConfig.worker?.serverBaseUrl?.trim()?.takeIf { it.isNotBlank() }
        if (configured != null) {
            return configured.right()
        }

        return WorkerSetupError.ServerUrlMissing(configDir.toString()).left()
    }

    /**
     * Resolves the worker UID for setup from the merged config, generating one when absent.
     *
     * @param mergedConfig Parsed merged worker config JSON.
     * @return Either a logical setup error or the resolved worker UID.
     */
    private fun resolveWorkerUid(mergedConfig: WorkerAppConfigDto): Either<WorkerSetupError, String> {
        val configured = mergedConfig.worker?.workerUid?.trim()?.takeIf { it.isNotBlank() }
        return configured?.right() ?: UUID.randomUUID().toString().right()
    }

    /**
     * Reuses valid worker secrets when available or generates and persists a fresh set.
     *
     * @param path Secrets file path to load or create.
     * @return Either a logical setup error or usable worker secrets.
     */
    private suspend fun loadOrGenerateSecrets(path: Path): Either<WorkerSetupError, WorkerSecrets> = either {
        val existing = readSecretsIfValid(path).bind()
        if (existing != null) {
            logger.info("Using existing worker secrets at {}", path)
            existing
        } else {
            logger.info("Generating new worker secrets at {}", path)
            val generated = certificateService.generateSecrets().bind()
            writeJson(path, generated).bind()
            generated
        }
    }

    /**
     * Reads `secrets.json` and returns the parsed secrets only when the certificate and
     * private key still form a valid pair.
     *
     * @param path Secrets file path to inspect.
     * @return Either a logical setup error or validated secrets when available.
     */
    private suspend fun readSecretsIfValid(path: Path): Either<WorkerSetupError, WorkerSecrets?> =
        withContext(Dispatchers.IO) {
            if (!Files.exists(path)) {
                null.right()
            } else {
                try {
                    val raw = Files.readString(path)
                    val secrets = json.decodeFromString<WorkerSecrets>(raw)
                    certificateService.validateSecrets(secrets, path.toString()).fold(
                        { null.right() },
                        { secrets.right() }
                    )
                } catch (_: Exception) {
                    logger.warn("Worker secrets are missing or invalid at {}; regenerating", path)
                    null.right()
                }
            }
        }

    /**
     * Writes worker secrets JSON to disk using the atomic file-write helper.
     *
     * @param path Target secrets file path.
     * @param value Secrets payload to serialize.
     * @return Either a logical setup error or `Unit` on successful write.
     */
    private suspend fun writeJson(path: Path, value: WorkerSecrets): Either<WorkerSetupError, Unit> =
        withContext(Dispatchers.IO) {
            writeJsonContent(path, json.encodeToString(WorkerSecrets.serializer(), value))
        }

    /**
     * Persists JSON content atomically by writing to a temporary file and moving it into place.
     *
     * @param path Final destination path for the JSON content.
     * @param content Serialized JSON text to persist.
     * @return Either a logical setup error or `Unit` on successful write.
     */
    private suspend fun writeJsonContent(path: Path, content: String): Either<WorkerSetupError, Unit> =
        withContext(Dispatchers.IO) {
            var tempFile: Path? = null
            try {
                val targetPath = path.toAbsolutePath().normalize()
                val targetDirectory = targetPath.parent ?: return@withContext WorkerSetupError.FileWriteFailed(
                    targetPath.toString(),
                    "Unable to resolve parent directory"
                ).left()

                Files.createDirectories(targetDirectory)
                tempFile = Files.createTempFile(targetDirectory, "${targetPath.fileName}.", ".tmp")
                Files.writeString(tempFile, content)

                try {
                    Files.move(
                        tempFile,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }

                Unit.right()
            } catch (e: Exception) {
                WorkerSetupError.FileWriteFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            } finally {
                if (tempFile != null && Files.exists(tempFile)) {
                    runCatching { Files.delete(tempFile) }
                        .onFailure { cleanupError ->
                            logger.debug(
                                "Failed to clean up temp setup file {}",
                                tempFile,
                                cleanupError
                            )
                        }
                }
            }
        }

    /**
     * Rebuilds the application-layer DTO values for `application.json` after setup.
     *
     * The resulting DTO keeps worker runtime values in the application layer while leaving
     * setup state out of the application file.
     *
     * @param existingConfig Existing merged config loaded from disk.
     * @param serverUrl Resolved server URL chosen for the completed setup.
     * @param workerUid Resolved worker UID used for the completed setup.
     * @param secretsPathValue Relative or absolute path to the worker secrets file.
     * @param tokenPathValue Relative or absolute path to the worker token file.
     * @param fingerprint Certificate fingerprint generated during setup.
     * @return A DTO ready to be persisted to `application.json`.
     */
    private fun buildUpdatedApplicationConfig(
        existingConfig: WorkerAppConfigDto,
        serverUrl: String,
        workerUid: String,
        secretsPathValue: String,
        tokenPathValue: String,
        fingerprint: String
    ): WorkerAppConfigDto {
        val existingWorker = existingConfig.worker ?: WorkerRuntimeConfigDto()

        return existingConfig.copy(
            worker = existingWorker.copy(
                serverBaseUrl = serverUrl,
                workerUid = workerUid,
                certificateFingerprint = fingerprint,
                secretsJsonPath = secretsPathValue,
                tokenFilePath = tokenPathValue
            ),
            setup = null
        )
    }

    /**
     * Builds the completion marker written to `setup.json` after a successful setup.
     *
     * The setup file intentionally contains only the `setup` section so the next startup can
     * see that setup has already completed and skip re-entering setup mode.
     *
     * @return A DTO ready to be persisted to `setup.json`.
     */
    private fun buildCompletedSetupConfig(): WorkerAppConfigDto {
        return WorkerAppConfigDto(
            setup = WorkerSetupConfigDto(required = false)
        )
    }

    /**
     * Resolves an absolute path directly or a relative path against the config directory.
     *
     * @param baseDirectory Base directory used for relative resolution.
     * @param configuredPath Configured path value.
     * @return Resolved normalized path.
     */
    private fun resolvePath(baseDirectory: Path, configuredPath: String): Path {
        val targetPath = Paths.get(configuredPath)
        return if (targetPath.isAbsolute) targetPath else baseDirectory.resolve(targetPath).normalize()
    }

    /**
     * Maps low-level configuration loader errors into setup-specific logical errors.
     *
     * @param error Configuration loader error returned by [WorkerConfigLoader].
     * @param configDir Active worker configuration directory used for path resolution.
     * @return Logical setup error suitable for surfacing to the caller.
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
        /**
         * Default relative path used for setup-generated worker secrets.
         */
        private const val DEFAULT_SECRETS_JSON_PATH = "./secrets.json"

        /**
         * Default relative path used for worker token cache persistence.
         */
        private const val DEFAULT_TOKEN_FILE_PATH = "./token.json"

        /**
         * Logger used by worker setup orchestration.
         */
        private val logger: Logger = LogManager.getLogger(DefaultWorkerSetupManager::class.java)
    }
}



