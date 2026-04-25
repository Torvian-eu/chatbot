package eu.torvian.chatbot.worker.main

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.config.*
import eu.torvian.chatbot.worker.koin.workerModule
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import eu.torvian.chatbot.worker.setup.*
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
 * to [WorkerRuntime]. Supports three execution modes:
 * - Normal runtime: loads config and runs indefinitely
 * - Setup mode: runs interactive setup flow to configure the worker
 * - Single-run mode: runs one job and exits
 *
 * @property configLoader Loader used for reading and writing layered config files.
 * @property secretsStore Store used to persist the private key to `secrets.json` during setup.
 * @property pathResolver Resolves relative storage paths against the config directory.
 * @property privateKeyProvider Resolves the PEM private key at runtime from env or secrets file.
 * @property setupManagerFactory Factory used to create the setup manager; overridden by tests.
 */
class WorkerMain(
    private val configLoader: WorkerConfigLoader = DefaultWorkerConfigLoader(),
    private val secretsStore: SecretsStore = FileSecretsStore(),
    private val pathResolver: PathResolver = PathResolver(),
    private val privateKeyProvider: PrivateKeyProvider = DefaultPrivateKeyProvider(),
    private val setupManagerFactory: () -> WorkerSetupManager = {
        DefaultWorkerSetupManager(configLoader, secretsStore = secretsStore)
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
     * @return Does not return normally; exits the process with status 1 on startup failure
     *         or status 0 on clean completion.
     */
    fun start(args: Array<String>) = runBlocking {
        logger.info("Worker process starting")
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
     * 2. Loads configuration from layered config directory
     * 3. Optionally runs interactive setup flow and exits cleanly on success
     * 4. Validates configuration and creates domain model
     * 5. Reads private key for TLS
     * 6. Boots Koin DI container
     * 7. Initializes HTTP client and runs worker runtime
     *
     * @param args Command-line arguments passed to the worker process.
     * @return Either a logical startup/runtime error or `Unit` on clean completion.
     */
    suspend fun run(args: Array<String>): Either<WorkerMainError, Unit> = either {
        val options = WorkerCliParser.parse(args)
        ensure(!(!options.setup && options.serverUrlOverride != null)) {
            WorkerMainError.InvalidArguments(
                "--server-url can only be used together with --setup"
            )
        }

        val configDir = configLoader.resolveConfigDir(options.configPathOverride)
        logger.info("Resolved worker config directory: {}", configDir)

        // Phase 1: Load the DTO (nullable/partial).
        val currentDto = configLoader.loadAppConfigDto(configDir)
            .mapLeft { WorkerMainError.Config(it) }
            .bind()
        logger.info("Initial worker configuration DTO loaded.")

        // Phase 2: Setup (explicit via --setup, or automatic when setup.required=true).
        // After successful setup the process exits cleanly so that provisioning and runtime remain distinct phases.
        if (options.setup || currentDto.setup?.required == true) {
            runWorkerSetup(configDir, currentDto, options.serverUrlOverride).bind()
            logger.info("Worker setup completed successfully. Start the worker again to begin normal operation.")
            return@either
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
        val httpClient = koin.get<HttpClient>()
        val runtime = koin.get<WorkerRuntime>()
        try {
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

}
