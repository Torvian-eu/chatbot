package eu.torvian.chatbot.worker.main

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.worker.config.AppConfigDto
import eu.torvian.chatbot.worker.config.SetupConfigDto
import eu.torvian.chatbot.worker.config.TrustedSignerUpdateResult
import eu.torvian.chatbot.worker.config.WorkerConfigError
import eu.torvian.chatbot.worker.config.WorkerConfigLoader
import eu.torvian.chatbot.worker.config.WorkerTrustedSignerManager
import eu.torvian.chatbot.worker.config.WorkerTrustedSignerManagerError
import eu.torvian.chatbot.worker.setup.WorkerSetupError
import eu.torvian.chatbot.worker.setup.WorkerSetupManager
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerMainTest {

    @Test
    fun `rejects server-url override without setup flag`() = runTest {
        val result = WorkerMain().run(arrayOf("--server-url=https://localhost:8443/"))

        assertTrue(result.leftOrNull() is WorkerMainError.InvalidArguments)
    }

    /**
     * Ensures signer-only flags are rejected unless trusted-signer admin mode was selected.
     */
    @Test
    fun `rejects signer specific flags without add trusted signer mode`() = runTest {
        val result = WorkerMain().run(arrayOf("--signer-id=signer-1"))

        assertTrue(result.leftOrNull() is WorkerMainError.InvalidArguments)
    }

    /**
     * Ensures trusted-signer admin mode still enforces its required signer payload arguments.
     */
    @Test
    fun `rejects add trusted signer mode without public key`() = runTest {
        val result = WorkerMain().run(arrayOf("--add-trusted-signer", "--signer-id=signer-1"))

        assertTrue(result.leftOrNull() is WorkerMainError.InvalidArguments)
    }

    /**
     * Verifies that trusted-signer admin mode returns before merged config loading or runtime boot.
     */
    @Test
    fun `trusted signer admin mode exits cleanly without loading runtime config`() = runTest {
        val configDir = createTempDirectory("worker-main-test")
        try {
            val configLoader = RecordingConfigLoader(configDir, AppConfigDto())
            val manager = RecordingTrustedSignerManager(
                TrustedSignerUpdateResult(
                    signerId = "signer-7",
                    applicationConfigPath = configDir.resolve("application.json"),
                    replacedExisting = false
                ).right()
            )
            val workerMain = WorkerMain(
                configLoader = configLoader,
                trustedSignerManagerFactory = { manager }
            )

            val result = workerMain.run(
                arrayOf(
                    "--add-trusted-signer",
                    "--config=${configDir.absolutePathString()}",
                    "--signer-id=signer-7",
                    "--public-key-base64=AQID",
                    "--permissions=mcp:read,mcp:write"
                )
            )

            assertTrue(result.isRight())
            assertEquals(0, configLoader.loadCalls)
            assertEquals(configDir, manager.lastConfigDir)
            assertEquals("signer-7", manager.lastSignerId)
            assertEquals("AQID", manager.lastPublicKeyBase64)
            assertEquals("mcp:read,mcp:write", manager.lastPermissionsCsv)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `setup mode invokes setup manager and exits successfully`() = runTest {
        val configDir = createTempDirectory("worker-main-test")
        try {
            val initialDto = AppConfigDto(setup = SetupConfigDto(required = true))
            val configLoader = RecordingConfigLoader(configDir, initialDto)
            val setupManager = RecordingSetupManager(Unit.right())
            val workerMain = WorkerMain(configLoader, setupManagerFactory = { setupManager })

            val result = workerMain.run(
                arrayOf(
                    "--setup",
                    "--config=${configDir.absolutePathString()}",
                    "--server-url=https://localhost:8443/"
                )
            )

            assertTrue(result.isRight())
            assertEquals(1, configLoader.loadCalls)
            assertEquals(configDir, setupManager.lastConfigDir)
            assertEquals(initialDto, setupManager.lastMergedConfig)
            assertEquals("https://localhost:8443/", setupManager.lastServerUrlOverride)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `automatic setup triggered by setup required exits successfully`() = runTest {
        val configDir = createTempDirectory("worker-main-test")
        try {
            val initialDto = AppConfigDto(setup = SetupConfigDto(required = true))
            val configLoader = RecordingConfigLoader(configDir, initialDto)
            val setupManager = RecordingSetupManager(Unit.right())
            val workerMain = WorkerMain(configLoader, setupManagerFactory = { setupManager })

            val result = workerMain.run(
                arrayOf(
                    "--config=${configDir.absolutePathString()}"
                )
            )

            assertTrue(result.isRight())
            assertEquals(1, configLoader.loadCalls)
            assertEquals(configDir, setupManager.lastConfigDir)
            assertEquals(initialDto, setupManager.lastMergedConfig)
            assertEquals(null, setupManager.lastServerUrlOverride)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `setup manager errors are wrapped as worker main setup errors`() = runTest {
        val configDir = createTempDirectory("worker-main-test")
        try {
            val configLoader = RecordingConfigLoader(configDir, AppConfigDto())
            val setupError = WorkerSetupError.LoginFailed("invalid credentials")
            val setupManager = RecordingSetupManager(setupError.left())
            val workerMain = WorkerMain(configLoader, setupManagerFactory = { setupManager })

            val result = workerMain.run(
                arrayOf(
                    "--setup",
                    "--config=${configDir.absolutePathString()}"
                )
            )

            val mainError = result.leftOrNull()
            assertTrue(mainError is WorkerMainError.Setup)
            assertEquals(setupError, mainError.error)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    private class RecordingConfigLoader(
        private val resolvedConfigDir: Path,
        private val dtoResult: AppConfigDto
    ) : WorkerConfigLoader {
        var loadCalls: Int = 0

        override fun resolveConfigDir(
            configDirOverride: String?,
            envProvider: (String) -> String?,
            propertyProvider: (String) -> String?
        ): Path = resolvedConfigDir

        override fun resolveLayerPath(configDir: Path, fileName: String): Path = configDir.resolve(fileName)

        override fun loadAppConfigDto(
            configDir: Path,
            envProvider: (String) -> String?
        ): Either<WorkerConfigError, AppConfigDto> {
            loadCalls += 1
            return dtoResult.right()
        }

        override fun loadLayerDto(
            configDir: Path,
            fileName: String,
            optional: Boolean
        ): Either<WorkerConfigError, AppConfigDto> = dtoResult.right()

        override fun saveLayerDto(
            configDir: Path,
            fileName: String,
            dto: AppConfigDto
        ): Either<WorkerConfigError, Unit> = Unit.right()
    }

    private class RecordingSetupManager(
        private val result: Either<WorkerSetupError, Unit>
    ) : WorkerSetupManager {
        var lastConfigDir: Path? = null
        var lastMergedConfig: AppConfigDto? = null
        var lastServerUrlOverride: String? = null

        override suspend fun run(
            configDir: Path,
            mergedConfig: AppConfigDto,
            serverUrlOverride: String?
        ): Either<WorkerSetupError, Unit> {
            lastConfigDir = configDir
            lastMergedConfig = mergedConfig
            lastServerUrlOverride = serverUrlOverride
            return result
        }
    }

    /**
     * Recording test double used to assert trusted-signer admin-mode orchestration inputs.
     *
     * @property result Result returned to the caller when admin mode is invoked.
     */
    private class RecordingTrustedSignerManager(
        private val result: Either<WorkerTrustedSignerManagerError, TrustedSignerUpdateResult>
    ) : WorkerTrustedSignerManager() {
        var lastConfigDir: Path? = null
        var lastSignerId: String? = null
        var lastPublicKeyBase64: String? = null
        var lastPermissionsCsv: String? = null

        override fun addOrUpdateTrustedSigner(
            configDir: Path,
            signerId: String,
            publicKeyBase64: String,
            permissionsCsv: String?
        ): Either<WorkerTrustedSignerManagerError, TrustedSignerUpdateResult> {
            lastConfigDir = configDir
            lastSignerId = signerId
            lastPublicKeyBase64 = publicKeyBase64
            lastPermissionsCsv = permissionsCsv
            return result
        }
    }
}


