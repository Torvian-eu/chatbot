package eu.torvian.chatbot.worker.config

import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerConfigLoaderTest {

    @Test
    fun `returns config missing when file does not exist`() {
        val missingPath = Path("does-not-exist-worker-config.json")

        val result = WorkerConfigLoader.load(missingPath)

        assertTrue(result.isLeft())
        assertTrue(result.swap().getOrNull() is WorkerConfigError.ConfigMissing)
    }

    @Test
    fun `returns config invalid when json is malformed`() {
        val tempDir = createTempDirectory("worker-config-test")
        val configPath = tempDir.resolve("config.json")

        configPath.writeText(
            """
            {
              "serverBaseUrl": "https://example.test",
              "workerId": 7
            }
            """.trimIndent()
        )

        try {
            val result = WorkerConfigLoader.load(configPath)
            assertTrue(result.isLeft())
            val error = result.swap().getOrNull()
            val configError = error as? WorkerConfigError.ConfigInvalid
            assertTrue(configError != null)
            assertTrue(configError.description.contains("Failed to parse JSON"))
        } finally {
            configPath.deleteIfExists()
            tempDir.deleteIfExists()
        }
    }

    @Test
    fun `rejects blank serverBaseUrl`() {
        val result = loadConfigWithOverrides(serverBaseUrl = "   ")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("serverBaseUrl must not be blank"))
    }

    @Test
    fun `rejects non-positive workerId`() {
        val result = loadConfigWithOverrides(workerId = 0)

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("workerId must be > 0"))
    }

    @Test
    fun `rejects malformed or unsupported serverBaseUrl`() {
        val malformed = loadConfigWithOverrides(serverBaseUrl = "example.test")
        assertTrue(malformed.isLeft())
        val malformedError = malformed.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(malformedError != null)

        val unsupported = loadConfigWithOverrides(serverBaseUrl = "ftp://example.test")
        assertTrue(unsupported.isLeft())
        val unsupportedError = unsupported.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(unsupportedError != null)
        assertTrue(unsupportedError.description.contains("http or https"))
    }

    @Test
    fun `rejects negative refresh skew`() {
        val result = loadConfigWithOverrides(refreshSkewSeconds = -1)

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("refreshSkewSeconds must be >= 0"))
    }

    @Test
    fun `rejects blank certificate fingerprint`() {
        val result = loadConfigWithOverrides(certificateFingerprint = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("certificateFingerprint must not be blank"))
    }

    @Test
    fun `resolveConfigPath prefers cli override over environment`() {
        val resolved = WorkerConfigLoader.resolveConfigPath(
            configPathOverride = "./cli-config.json",
            envProvider = { "./env-config.json" }
        )

        assertEquals(Path("./cli-config.json"), resolved)
    }

    @Test
    fun `resolveConfigPath falls back to environment when cli override is absent`() {
        val resolved = WorkerConfigLoader.resolveConfigPath(
            configPathOverride = null,
            envProvider = { "./env-config.json" }
        )

        assertEquals(Path("./env-config.json"), resolved)
    }

    @Test
    fun `resolveConfigPath falls back to default when cli override and environment are absent`() {
        val resolved = WorkerConfigLoader.resolveConfigPath(
            configPathOverride = null,
            envProvider = { null }
        )

        assertEquals(Path("./worker-config/config.json"), resolved)
    }

    private fun loadConfigWithOverrides(
        serverBaseUrl: String = "https://example.test",
        workerId: Long = 7,
        certificateFingerprint: String = "fp",
        privateKeyPemPath: String = "./worker-key.pem",
        tokenFilePath: String = "./token.json",
        refreshSkewSeconds: Long = 60
    ): arrow.core.Either<WorkerConfigError, WorkerRuntimeConfig> {
        val tempDir = createTempDirectory("worker-config-test")
        val configPath = tempDir.resolve("config.json")
        configPath.writeText(
            """
            {
              "serverBaseUrl": "$serverBaseUrl",
              "workerId": $workerId,
              "certificateFingerprint": "$certificateFingerprint",
              "privateKeyPemPath": "$privateKeyPemPath",
              "tokenFilePath": "$tokenFilePath",
              "refreshSkewSeconds": $refreshSkewSeconds
            }
            """.trimIndent()
        )

        return try {
            WorkerConfigLoader.load(configPath)
        } finally {
            configPath.deleteIfExists()
            tempDir.deleteIfExists()
        }
    }
}


