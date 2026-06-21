package eu.torvian.chatbot.worker.main

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.config.*
import eu.torvian.chatbot.worker.koin.workerModule
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import eu.torvian.chatbot.worker.service.api.WorkerMetadataService
import eu.torvian.chatbot.worker.setup.*
import eu.torvian.chatbot.worker.VersionInfo
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Path
import org.apache.logging.log4j.Logger as Log4jLogger

/**
 * Standalone worker process entrypoint.
 *
 * The worker loads its layered config directory, boots DI, and delegates runtime orchestration
 * to [WorkerRuntime]. Supports four execution modes:
 * - Normal runtime: loads config and runs indefinitely
 * - Setup mode: runs interactive setup flow to configure the worker
 * - Single-run mode: runs one job and exits
 * - Trusted-signer admin mode: patches `application.json` and exits before runtime boot
 *
 * @property configLoader Loader used for reading and writing layered config files.
 * @property secretsStore Store used to persist the private key to `secrets.json` during setup.
 * @property pathResolver Resolves relative storage paths against the config directory.
 * @property privateKeyProvider Resolves the PEM private key at runtime from env or secrets file.
 * @property setupManagerFactory Factory used to create the setup manager; overridden by tests.
 * @property trustedSignerManagerFactory Factory used to create the trusted-signer admin helper; overridden by tests.
 */
class WorkerMain(
    private val configLoader: WorkerConfigLoader = DefaultWorkerConfigLoader(),
    private val secretsStore: SecretsStore = FileSecretsStore(),
    private val pathResolver: PathResolver = PathResolver(),
    private val privateKeyProvider: PrivateKeyProvider = DefaultPrivateKeyProvider(),
    private val setupManagerFactory: () -> WorkerSetupManager = {
        DefaultWorkerSetupManager(configLoader, secretsStore = secretsStore)
    },
    private val trustedSignerManagerFactory: () -> WorkerTrustedSignerManager = {
        WorkerTrustedSignerManager(configLoader)
    }
) {
    companion object {
        /**
         * JVM entrypoint used by the Java launcher.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            WorkerMain().start(args)
        }

        const val SETUP_AUTO_START_ENV_VAR = "CHATBOT_WORKER_SETUP_AUTO_START"
    }

    /**
     * Logger used for worker startup/runtime lifecycle logs.
     */
    private val logger: Log4jLogger = LogManager.getLogger(WorkerMain::class.java)

    /**
     * Boots the worker process for this instance.
     *
     * Entry point for the standalone worker application. Parses command-line arguments,
     * loads configuration, optionally runs setup, initializes the DI container, and
     * executes the worker runtime. On any startup error, logs the error and exits with status 1.
     *
     * @param args Command-line arguments. Supports:
     *   - `--config=<config-dir>` Override config directory path
     *   - `--setup` Run interactive setup flow and exit
     *   - `--once` Run a single job and exit instead of infinite runtime
     *   - `--server-url=<url>` Override server base URL during setup (use with --setup only)
     *   - `--add-trusted-signer` Add or replace one trusted signer in `application.json` and exit
     *   - `--signer-id=<id>` Signer identifier used with `--add-trusted-signer`
     *   - `--public-key-base64=<base64>` Signer public key used with `--add-trusted-signer`
     *   - `--permissions=<comma,separated,list>` Optional permissions used with `--add-trusted-signer`
     * @return Does not return normally; exits the process with status 1 on startup failure
     *         or status 0 on clean completion.
     */
    fun start(args: Array<String>) = runBlocking {
        logger.info("Starting Torvian Chatbot Worker v${VersionInfo.VERSION}...")
        run(args).fold(
            { error ->
                logger.error("Worker startup failed: {}", error)
                kotlin.system.exitProcess(1)
            },
            { logger.info("Worker process finished cleanly") }
        )
    }

    /**
     * Testable startup runner that performs bootstrap and executes the runtime.
     *
     * Orchestrates the full worker startup pipeline:
     * 1. Parses CLI arguments
     * 2. Resolves the config directory and optionally performs trusted-signer admin mode
     * 3. Loads configuration from layered config directory
     * 4. Optionally runs interactive setup flow and exits cleanly on success
     * 5. Validates configuration and creates domain model
     * 6. Reads private key for TLS
     * 7. Boots Koin DI container
     * 8. Initializes HTTP client and runs worker runtime
     *
     * @param args Command-line arguments passed to the worker process.
     * @return Either a logical startup/runtime error or `Unit` on clean completion.
     */
    suspend fun run(args: Array<String>): Either<WorkerMainError, Unit> = either {
        val options = WorkerCliParser.parse(args)
        validateCliOptions(options).bind()

        val configDir = configLoader.resolveConfigDir(options.configPathOverride)
        logger.info("Resolved worker config directory: {}", configDir)

        if (options.addTrustedSigner) {
            runTrustedSignerAdminMode(configDir, options).bind()
            return@either
        }

        // Phase 1: Load the DTO (nullable/partial).
        var currentDto = configLoader.loadAppConfigDto(configDir)
            .mapLeft { WorkerMainError.Config(it) }
            .bind()
        logger.info("Initial worker configuration DTO loaded.")

        // Phase 2: Setup (explicit via --setup, or automatic when setup.required=true).
        // After successful setup the process exits cleanly so that provisioning and runtime remain distinct phases.
        // Exception: If CHATBOT_WORKER_SETUP_AUTO_START=true, transition directly to normal operation.
        if (options.setup || currentDto.setup?.required == true) {
            runWorkerSetup(configDir, currentDto, options.serverUrlOverride).bind()

            // Check if we should auto-start after successful setup
            val autoStartAfterSetup = System.getenv(SETUP_AUTO_START_ENV_VAR)?.lowercase() == "true"

            if (autoStartAfterSetup) {
                logger.info("Reloading configuration after setup...")
                currentDto = configLoader.loadAppConfigDto(configDir)
                    .mapLeft { WorkerMainError.Config(it) }
                    .bind()
                logger.info("Worker setup completed successfully. Starting normal operation...")
            } else {
                logger.info("Worker setup completed successfully. Start the worker again to begin normal operation.")
                return@either
            }
        }

        // Phase 3: Strict assembly and validation.
        val appConfig = currentDto.toDomain()
            .mapLeft { WorkerMainError.Config(it) }
            .bind()
        val config = appConfig.worker

        val resolvedPaths = pathResolver.resolve(configDir, config.storage)
        val privateKeyPem = privateKeyProvider.loadPrivateKeyPem(configDir, config.storage)
            .mapLeft { error ->
                when (error) {
                    is PrivateKeyLoadError.Unavailable ->
                        WorkerMainError.SecretsReadFailed("env", error.reason)

                    is PrivateKeyLoadError.SecretsReadFailed ->
                        WorkerMainError.SecretsReadFailed(error.path, error.reason)
                }
            }
            .bind()
        val tokenPath = resolvedPaths.tokenPath

        val koinApplication = startKoin {
            modules(workerModule(config, tokenPath, privateKeyPem))
        }
        val koin = koinApplication.koin
        val metadataService = koin.get<WorkerMetadataService>()
        val httpClient = koin.get<HttpClient>()
        val runtime = koin.get<WorkerRuntime>()
        try {
            metadataService.checkCompatibility().fold(
                ifLeft = { error ->
                    logger.warn("Worker/server compatibility probe reported a logical issue; continuing startup: {}", error)
                },
                ifRight = {
                    // Compatibility mismatches are handled inside the service by logging a warning.
                }
            )
            logger.info("Worker HTTP client initialized for {}", config.server.baseUrl)
            runtime.run(options.runOnce).mapLeft { WorkerMainError.Runtime(it) }.bind()
        } finally {
            runtime.close()
            httpClient.close()
            stopKoin()
        }
    }

    /**
     * Runs the interactive worker setup flow.
     *
     * Delegates to [DefaultWorkerSetupManager] to guide the operator through configuring
     * the worker, including establishing secure channel to the server and storing secrets.
     * On success, updates the configuration files in the config directory.
     *
     * @param configDir Worker config directory to save setup configuration to.
     * @param serverUrlOverride Optional override of server URL.
     * @return Either a setup error or `Unit` when setup completed.
     */
    private suspend fun runWorkerSetup(
        configDir: Path,
        mergedConfig: AppConfigDto,
        serverUrlOverride: String?
    ): Either<WorkerMainError, Unit> {
        logger.info("Running worker setup...")
        val setupManager = setupManagerFactory()
        return setupManager.run(configDir, mergedConfig, serverUrlOverride)
            .mapLeft { WorkerMainError.Setup(it) }
    }

    /**
     * Validates command-line argument combinations before worker startup proceeds.
     *
     * @param options Parsed command-line options.
     * @return Either an invalid-arguments error or `Unit` when the options are acceptable.
     */
    private fun validateCliOptions(options: WorkerCliOptions): Either<WorkerMainError.InvalidArguments, Unit> = either {
        ensure(!(!options.setup && options.serverUrlOverride != null)) {
            WorkerMainError.InvalidArguments(
                "--server-url can only be used together with --setup"
            )
        }

        ensure(!(options.addTrustedSigner && options.signerId == null)) {
            WorkerMainError.InvalidArguments(
                "--signer-id is required when --add-trusted-signer is used"
            )
        }

        ensure(!(options.addTrustedSigner && options.publicKeyBase64 == null)) {
            WorkerMainError.InvalidArguments(
                "--public-key-base64 is required when --add-trusted-signer is used"
            )
        }

        val hasSignerSpecificFlags = options.signerId != null ||
            options.publicKeyBase64 != null ||
            options.permissionsCsv != null
        ensure(!(hasSignerSpecificFlags && !options.addTrustedSigner)) {
            WorkerMainError.InvalidArguments(
                "--signer-id, --public-key-base64, and --permissions can only be used together with --add-trusted-signer"
            )
        }
    }

    /**
     * Runs the trusted-signer admin mode and exits before runtime bootstrap.
     *
     * @param configDir Worker config directory containing `application.json`.
     * @param options Parsed CLI options for trusted-signer admin mode.
     * @return Either a logical startup error or `Unit` when the signer update completed.
     */
    private fun runTrustedSignerAdminMode(
        configDir: Path,
        options: WorkerCliOptions
    ): Either<WorkerMainError, Unit> {
        val trustedSignerManager = trustedSignerManagerFactory()
        return trustedSignerManager.addOrUpdateTrustedSigner(
            configDir = configDir,
            signerId = options.signerId.orEmpty(),
            publicKeyBase64 = options.publicKeyBase64.orEmpty(),
            permissionsCsv = options.permissionsCsv
        ).mapLeft { error ->
            when (error) {
                is WorkerTrustedSignerManagerError.InvalidInput ->
                    WorkerMainError.InvalidArguments(error.error.description)

                is WorkerTrustedSignerManagerError.Config ->
                    WorkerMainError.Config(error.error)
            }
        }.map {
            val operation = if (it.replacedExisting) "Updated" else "Added"
            logger.info(
                "{} trusted signer '{}' in {}",
                operation,
                it.signerId,
                it.applicationConfigPath
            )
        }
    }

}
