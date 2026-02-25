package eu.torvian.chatbot.server.main

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import eu.torvian.chatbot.server.config.*
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.config.ServerConnectorType
import eu.torvian.chatbot.server.domain.config.SslConfig
import eu.torvian.chatbot.server.service.security.DefaultCertificateManager
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.security.SecureRandom
import java.util.*
import kotlin.system.exitProcess

/**
 * Main entry point for the Chatbot Server application.
 *
 * Orchestrates the server lifecycle through four distinct phases:
 * 1. **Configuration**: Loads JSON files (application, secrets, setup) and environment variables.
 * 2. **Setup**: Automatically generates missing secrets or SSL certificates if required.
 * 3. **Validation**: Verifies that the resulting environment is sane and ready for startup.
 * 4. **Execution**: Starts the Ktor engine and enters the CLI control loop.
 */
object ServerMain {
    private val logger: Logger = LogManager.getLogger(ServerMain::class.java)

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val program = either {
            // Phase 1: Load the DTO (Nullable/Partial)
            var currentDto = ServerConfigLoader.loadConfigDto().bind()
            logger.info("Initial configuration DTO loaded.")

            // Phase 2: Setup (If required)
            // We check against true explicitly because setup?.required is nullable
            if (currentDto.setup?.required == true) {
                currentDto = runServerSetup(currentDto)
            }

            // Phase 3: Strict Assembly & Logic Validation
            // This is where we convert the merged DTO (Files + Generated + Env)
            // into the strict Domain object.
            val config = currentDto.toDomain(ServerConfigLoader.resolveBaseApplicationPath()).bind()
            validateEnvironment(config)

            // Phase 4: Execution
            val serverControl = createServerControl(config)
            try {
                serverControl.startSuspend()

                // At this point, the server is guaranteed started
                val serverInfo = serverControl.getServerInfo()!!
                logger.info("Server active on: ${serverInfo.baseUri}")

                runConsoleLoop()
            } finally {
                logger.info("Initiating graceful shutdown...")
                serverControl.stopSuspend(100, 3000)
            }
        }

        // Global Error Handling
        program.onLeft { error ->
            when (error) {
                is ConfigError.FileError -> logger.error("CONFIG FILE ERROR: ${error.toMessage()}")
                is ConfigError.ValidationError -> logger.error("DATA VALIDATION ERROR: ${error.toMessage()}")
                is ConfigError.EnvironmentError -> logger.error("ENVIRONMENT ERROR: ${error.toMessage()}")
            }
            exitProcess(1)
        }

        logger.info("Program exited cleanly.")
    }

    /**
     * Executes the setup phase to generate missing secrets and certificates.
     *
     * This function persists data into two specific files:
     * - `config/secrets.json`: Stores newly generated passwords and encryption keys.
     * - `config/setup.json`: Updates the status to mark setup as complete.
     *
     * This function acts as the bootstrap validator and generator.
     * It ensures that by the time it returns, the DTO contains everything
     * required for a successful [toDomain] conversion.
     *
     * @param dto The current, potentially incomplete configuration DTO.
     * @return The updated [AppConfigDto] containing all generated/validated values.
     */
    private fun Raise<ConfigError>.runServerSetup(dto: AppConfigDto): AppConfigDto {
        logger.info("Running server setup...")

        // Accumulates only the values we generate/default during this session
        var setupDelta = AppConfigDto()

        val dbDto = dto.database

        // --- 1. SSL / Certificate Setup ---
        val networkDto = dto.network
        val connectorType =
            networkDto?.connectorType?.let { ServerConnectorType.fromString(it) } ?: ServerConnectorType.HTTPS

        if (connectorType != ServerConnectorType.HTTP) {
            val sslDto = dto.ssl
            ensureNotNull(sslDto) {
                ConfigError.ValidationError.MissingKey("ssl (Required for SSL)")
            }
            val autoGenerateSsl = sslDto.generateSelfSigned ?: true

            // Resolve keystorePath from StorageConfig (parent of config dir + dataDir + keystoreFilename)
            val baseAppPath = ServerConfigLoader.resolveBaseApplicationPath()
            val storageDto = dto.storage
            val dataDir = storageDto?.dataDir ?: raise(ConfigError.ValidationError.MissingKey("storage.dataDir"))
            val keystoreFilename = storageDto.keystoreFilename ?: raise(ConfigError.ValidationError.MissingKey("storage.keystoreFilename"))
            val ksPath = "$baseAppPath/$dataDir/$keystoreFilename"
            val ksFile = File(ksPath)

            if (autoGenerateSsl) {
                if (ksFile.exists()) {
                    // CASE 1: Auto-mode, but file exists. Verify we have the keys to open it.
                    validateExistingSsl(ksPath, ksFile, sslDto, "Automatic")
                    logger.info("SSL: Using existing keystore in automatic mode.")
                } else {
                    // CASE 2: Auto-mode, file missing. Generate everything.
                    val ksPass = sslDto.keystorePassword?.takeIf { it.isNotBlank() } ?: generatePassword(32)
                    val keyPass = sslDto.keyPassword?.takeIf { it.isNotBlank() } ?: generatePassword(32)
                    val ksAlias = sslDto.keyAlias ?: "serverKey"

                    logger.info("SSL: Generating new self-signed certificate at $ksPath...")
                    val domainSsl = SslConfig(
                        port = sslDto.port ?: 8443,
                        keystorePath = ksPath,
                        keystorePassword = ksPass,
                        keyAlias = ksAlias,
                        keyPassword = keyPass,
                        generateSelfSigned = true
                    )
                    DefaultCertificateManager(domainSsl).generateServerCertificate()

                    // Track what we generated so we can save it to secrets.json
                    setupDelta = setupDelta.merge(
                        AppConfigDto(
                            ssl = SslConfigDto(
                                keyAlias = ksAlias,
                                keystorePassword = ksPass,
                                keyPassword = keyPass
                            )
                        )
                    )
                }
            } else {
                // CASE 3: Manual mode. We do not generate anything; we strictly validate.
                validateExistingSsl(ksPath, ksFile, sslDto, "Manual")
                logger.info("SSL: Manual configuration validated successfully.")
            }
        }

        // --- 2. Encryption Master Key ---
        val keyVersion = dto.encryption?.keyVersion ?: 1
        if (dto.encryption?.masterKeys?.get(keyVersion).isNullOrBlank()) {
            logger.info("Encryption: Generating master key (v$keyVersion)...")
            setupDelta = setupDelta.merge(
                AppConfigDto(
                    encryption = EncryptionConfigDto(
                        masterKeys = mapOf(keyVersion to generateBase64Key(32))
                    )
                )
            )
        }

        // --- 3. JWT Secret ---
        if (dto.jwt?.secret.isNullOrBlank()) {
            logger.info("JWT: Generating signing secret...")
            setupDelta = setupDelta.merge(
                AppConfigDto(
                    jwt = JwtConfigDto(secret = generatePassword(64))
                )
            )
        }

        // --- 4. Database File Creation (if type is 'file') ---
        if (dbDto?.type == "file") {
            // Resolve full database path from StorageConfig components
            val baseAppPath = ServerConfigLoader.resolveBaseApplicationPath()
            val dataDir = dto.storage?.dataDir ?: raise(ConfigError.ValidationError.MissingKey("storage.dataDir"))
            val databaseFilename = dto.storage.databaseFilename ?: raise(ConfigError.ValidationError.MissingKey("storage.databaseFilename"))
            val resolvedDatabasePath = "$baseAppPath/$dataDir/$databaseFilename"

            val tempDbConfig = DatabaseConfig(
                vendor = dbDto.vendor ?: raise(ConfigError.ValidationError.MissingKey("database.vendor")),
                type = dbDto.type,
                filepath = resolvedDatabasePath
            )
            val dbFile = File(tempDbConfig.filepath)

            if (!dbFile.exists()) {
                logger.info("Database: Creating new database file at '${tempDbConfig.filepath}'...")
                dbFile.parentFile?.mkdirs() // Ensure parent directories exist
                try {
                    dbFile.createNewFile()
                    logger.info("Database: File created successfully.")
                } catch (e: Exception) {
                    raise(
                        ConfigError.EnvironmentError.CreationFailure(
                            tempDbConfig.filepath,
                            e.message ?: "Unknown I/O error"
                        )
                    )
                }
            } else {
                logger.info("Database: File already exists at '${tempDbConfig.filepath}'. Using existing file.")
            }
            ensure(dbFile.canWrite()) {
                ConfigError.EnvironmentError.PermissionDenied(tempDbConfig.filepath, "WRITE")
            }
        }

        // --- 5. Persistence ---
        if (setupDelta != AppConfigDto()) {
            ServerConfigLoader.saveConfig(setupDelta, "secrets.json")
            logger.info("Setup: New values saved to config/secrets.json")
        }

        // Mark setup as complete
        val setupFinished = AppConfigDto(setup = SetupConfigDto(required = false))
        ServerConfigLoader.saveConfig(setupFinished, "setup.json")

        // Return the enriched DTO
        return dto.merge(setupDelta).merge(setupFinished)
    }

    /**
     * Helper to ensure an existing keystore has a valid path and all required credentials.
     *
     * @param path The absolute path to the keystore file.
     * @param file The [File] instance for the keystore.
     * @param dto The SSL configuration DTO containing credentials.
     * @param mode The setup mode (e.g. "Automatic", "Manual").
     */
    private fun Raise<ConfigError.ValidationError>.validateExistingSsl(
        path: String,
        file: File,
        dto: SslConfigDto?,
        mode: String
    ) {
        ensure(file.exists()) {
            ConfigError.ValidationError.InvalidValue(
                "ssl.keystorePath",
                "$mode mode: Keystore file not found at '$path'."
            )
        }

        val missing = mutableListOf<String>()
        if (dto?.keystorePassword.isNullOrBlank()) missing.add("ssl.keystorePassword")
        if (dto?.keyPassword.isNullOrBlank()) missing.add("ssl.keyPassword")
        if (dto?.keyAlias.isNullOrBlank()) missing.add("ssl.keyAlias")

        if (missing.isNotEmpty()) {
            raise(
                ConfigError.ValidationError.InvalidValue(
                    "ssl",
                    "$mode mode: Keystore file exists, but required credentials (${missing.joinToString()}) are missing."
                )
            )
        }
    }

    /**
     * Validates the runtime environment after any setup logic has completed.
     * Ensures all required files (like keystores) are physically present.
     *
     * @param config The loaded application configuration.
     */
    private fun Raise<ConfigError>.validateEnvironment(config: AppConfiguration) {
        // Check SSL Requirements
        if (config.network.connectorType != ServerConnectorType.HTTP) {
            val ssl = config.ssl
                ?: raise(ConfigError.EnvironmentError.ResourceMissing("ssl", "SSL configuration"))

            ensure(File(ssl.keystorePath).exists()) {
                ConfigError.EnvironmentError.ResourceMissing(ssl.keystorePath, "Keystore")
            }
        }

        // Check Database File Requirements
        if (config.database.type == "file") {
            val dbFile = File(config.database.filepath)
            ensure(dbFile.exists()) {
                ConfigError.EnvironmentError.ResourceMissing(config.database.filepath, "Database file")
            }
            ensure(dbFile.canWrite()) {
                ConfigError.EnvironmentError.PermissionDenied(config.database.filepath, "WRITE")
            }
        }

        // Check Security Secrets (ensure they aren't empty)
        ensure(!config.encryption.masterKeys[config.encryption.keyVersion].isNullOrBlank()) {
            ConfigError.ValidationError.MissingKey(
                "encryption.masterKeys.${config.encryption.keyVersion}"
            )
        }
        ensure(!config.jwt.secret.isBlank()) { ConfigError.ValidationError.MissingKey("jwt.secret") }
    }

    /**
     * Manages the CLI console loop.
     * Detects "exit" or a double-press of the Enter key to trigger shutdown.
     */
    private fun runConsoleLoop() {
        val reader = System.`in`.bufferedReader()
        var emptyLineCount = 0

        while (true) {
            val input = try {
                reader.readLine()?.trim() ?: ""
            } catch (_: Exception) {
                "exit"
            }

            if (input.lowercase() == "exit") break

            if (input.isEmpty()) {
                emptyLineCount++
                if (emptyLineCount >= 2) {
                    logger.info("Shutdown triggered via console.")
                    break
                }
            } else {
                emptyLineCount = 0
                println("Unknown command: '$input'. Type 'exit' to quit.")
            }
        }
    }

    /**
     * Dependency injection helper to create the Server Control implementation.
     *
     * @param config The validated application configuration.
     * @return An instance of [ServerControlServiceImpl].
     */
    private fun createServerControl(config: AppConfiguration): ServerControlServiceImpl {
        // Only instantiate CertificateManager if SSL is active
        val certManager = config.ssl?.let { DefaultCertificateManager(it) }

        return ServerControlServiceImpl(
            networkConfig = config.network,
            sslConfig = config.ssl,
            certificateManager = certManager,
            databaseConfig = config.database,
            encryptionConfig = config.encryption,
            jwtConfig = config.jwt
        )
    }

    /**
     * Generates a cryptographically secure random password.
     */
    private fun generatePassword(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+"
        val random = SecureRandom()
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Generates a cryptographically secure random key encoded in Base64.
     */
    private fun generateBase64Key(bytes: Int): String {
        val keyData = ByteArray(bytes)
        SecureRandom().nextBytes(keyData)
        return Base64.getEncoder().encodeToString(keyData)
    }
}