package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.either
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerConfigLoaderTest {

    private val loader = DefaultWorkerConfigLoader()

    @Test
    fun `loads valid worker config successfully`() {
        val result = loadConfigWithOverrides(
            serverBaseUrl = "https://example.test",
            workerUid = "worker-7",
            certificateFingerprint = "fingerprint-1",
            secretsJsonPath = "./secrets.json",
            tokenFilePath = "./token.json",
            refreshSkewSeconds = 75
        )

        assertTrue(result.isRight())
        val config = result.getOrNull()
        assertEquals("https://example.test", config?.worker?.serverBaseUrl)
        assertEquals("worker-7", config?.worker?.workerUid)
        assertEquals("fingerprint-1", config?.worker?.certificateFingerprint)
        assertEquals("./secrets.json", config?.worker?.secretsJsonPath)
        assertEquals("./token.json", config?.worker?.tokenFilePath)
        assertEquals(75, config?.worker?.refreshSkewSeconds)
    }

    @Test
    fun `returns config invalid when worker group is missing`() {
        val configDir = createTempDirectory("worker-config-test")
        configDir.resolve("application.json").writeText("{}")
        configDir.resolve("setup.json").writeText("{}")
        configDir.resolve("env-mapping.json").writeText("{}")

        try {
            val result = either {
                val dto = loader.loadAppConfigDto(configDir).bind()
                dto.toDomain().bind()
            }
            assertTrue(result.isLeft())
            val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
            assertTrue(error != null)
            assertTrue(error.description.contains("Missing required config group: worker"))
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns config invalid when json is malformed`() {
        val tempDir = createTempDirectory("worker-config-test")
        val configPath = tempDir.resolve("application.json")
        tempDir.resolve("setup.json").writeText("{}")
        tempDir.resolve("env-mapping.json").writeText("{}")

        configPath.writeText(
            """
            {
              "worker": {
                "serverBaseUrl": "https://example.test"
                "workerUid": "worker-7"
              }
            }
            """.trimIndent()
        )

        try {
            val result = either {
                val dto = loader.loadAppConfigDto(tempDir).bind()
                dto.toDomain().bind()
            }
            assertTrue(result.isLeft())
            val error = result.swap().getOrNull()
            val configError = error as? WorkerConfigError.ConfigInvalid
            assertTrue(configError != null)
            assertTrue(configError.description.contains("Failed to parse JSON"))
        } finally {
            tempDir.toFile().deleteRecursively()
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
    fun `rejects blank workerUid`() {
        val result = loadConfigWithOverrides(workerUid = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.workerUid must not be blank"))
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
        assertTrue(error.description.contains("worker.refreshSkewSeconds must be >= 0"))
    }

    @Test
    fun `rejects blank certificate fingerprint`() {
        val result = loadConfigWithOverrides(certificateFingerprint = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.certificateFingerprint must not be blank"))
    }

    @Test
    fun `rejects blank secrets json path`() {
        val result = loadConfigWithOverrides(secretsJsonPath = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.secretsJsonPath must not be blank"))
    }

    @Test
    fun `setup layer overrides base layer`() {
        val configDir = createTempDirectory("worker-config-test")
        writeBaseConfig(configDir, workerUid = "worker-base", serverBaseUrl = "https://base.test")
        configDir.resolve("setup.json").writeText(
            """
            {
              "worker": {
                "workerUid": "worker-setup"
              }
            }
            """.trimIndent()
        )
        configDir.resolve("env-mapping.json").writeText("{}")

        try {
            val result = either {
                val dto = loader.loadAppConfigDto(configDir).bind()
                dto.toDomain().bind()
            }
            assertTrue(result.isRight())
            assertEquals("worker-setup", result.getOrNull()?.worker?.workerUid)
            assertEquals("https://base.test", result.getOrNull()?.worker?.serverBaseUrl)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `env mapping overrides setup and base`() {
        val configDir = createTempDirectory("worker-config-test")
        writeBaseConfig(configDir, workerUid = "worker-base", serverBaseUrl = "https://base.test")
        configDir.resolve("setup.json").writeText(
            """
            {
              "worker": {
                "serverBaseUrl": "https://setup.test"
              }
            }
            """.trimIndent()
        )
        configDir.resolve("env-mapping.json").writeText(
            """
            {
              "worker": {
                "serverBaseUrl": "WORKER_SERVER_BASE_URL"
              }
            }
            """.trimIndent()
        )

        try {
            val result = either {
                val dto = loader.loadAppConfigDto(
                    configDir,
                    envProvider = { key -> if (key == "WORKER_SERVER_BASE_URL") "https://env.test" else null }
                ).bind()
                dto.toDomain().bind()
            }
            assertTrue(result.isRight())
            assertEquals("https://env.test", result.getOrNull()?.worker?.serverBaseUrl)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unresolved env mapping does not override lower layers`() {
        val configDir = createTempDirectory("worker-config-test")
        writeBaseConfig(configDir, workerUid = "worker-base", serverBaseUrl = "https://base.test")
        configDir.resolve("setup.json").writeText("{}")
        configDir.resolve("env-mapping.json").writeText(
            """
            {
              "worker": {
                "workerUid": "WORKER_UID"
              }
            }
            """.trimIndent()
        )

        try {
            val result = either {
                val dto = loader.loadAppConfigDto(configDir, envProvider = { null }).bind()
                dto.toDomain().bind()
            }
            assertTrue(result.isRight())
            assertEquals("worker-base", result.getOrNull()?.worker?.workerUid)
            assertEquals("https://base.test", result.getOrNull()?.worker?.serverBaseUrl)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveConfigDir prefers cli override over environment and property`() {
        val resolved = loader.resolveConfigDir(
            configDirOverride = "./cli-config",
            envProvider = { "./env-config" },
            propertyProvider = { "./prop-config" }
        )

        assertEquals(Path("./cli-config"), resolved)
    }

    @Test
    fun `resolveConfigDir falls back to environment then property then default`() {
        val fromEnv = loader.resolveConfigDir(
            configDirOverride = null,
            envProvider = { "./env-config" },
            propertyProvider = { "./prop-config" }
        )
        assertEquals(Path("./env-config"), fromEnv)

        val fromProperty = loader.resolveConfigDir(
            configDirOverride = null,
            envProvider = { null },
            propertyProvider = { "./prop-config" }
        )
        assertEquals(Path("./prop-config"), fromProperty)

        val fromDefault = loader.resolveConfigDir(
            configDirOverride = null,
            envProvider = { null },
            propertyProvider = { null }
        )
        assertEquals(Path("./worker-config"), fromDefault)
    }

    @Test
    fun `loadConfiguration returns root config including setup required`() {
        val configDir = createTempDirectory("worker-config-test")
        writeBaseConfig(configDir, workerUid = "worker-root", serverBaseUrl = "https://root.test")
        configDir.resolve("setup.json").writeText(
            """
            {
              "setup": {
                "required": false
              }
            }
            """.trimIndent()
        )
        configDir.resolve("env-mapping.json").writeText("{}")

        try {
            val result = either {
                val dto = loader.loadAppConfigDto(configDir).bind()
                dto.toDomain().bind()
            }
            assertTrue(result.isRight())
            val config = result.getOrNull()
            assertEquals(false, config?.setupRequired)
            assertEquals("worker-root", config?.worker?.workerUid)
            assertEquals("https://root.test", config?.worker?.serverBaseUrl)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    private fun loadConfigWithOverrides(
        serverBaseUrl: String = "https://example.test",
        workerUid: String = "worker-7",
        certificateFingerprint: String = "fp",
        secretsJsonPath: String = "./secrets.json",
        tokenFilePath: String = "./token.json",
        refreshSkewSeconds: Long = 60
    ): Either<WorkerConfigError, WorkerConfiguration> {
        val tempDir = createTempDirectory("worker-config-test")
        writeBaseConfig(
            tempDir,
            serverBaseUrl = serverBaseUrl,
            workerUid = workerUid,
            certificateFingerprint = certificateFingerprint,
            secretsJsonPath = secretsJsonPath,
            tokenFilePath = tokenFilePath,
            refreshSkewSeconds = refreshSkewSeconds
        )
        tempDir.resolve("setup.json").writeText("{}")
        tempDir.resolve("env-mapping.json").writeText("{}")

        return try {
            either {
                val dto = loader.loadAppConfigDto(tempDir).bind()
                dto.toDomain().bind()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun writeBaseConfig(
        configDir: java.nio.file.Path,
        serverBaseUrl: String,
        workerUid: String,
        certificateFingerprint: String = "fp",
        secretsJsonPath: String = "./secrets.json",
        tokenFilePath: String = "./token.json",
        refreshSkewSeconds: Long = 60
    ) {
        configDir.resolve("application.json").writeText(
            """
            {
              "worker": {
                "serverBaseUrl": "$serverBaseUrl",
                "workerUid": "$workerUid",
                "certificateFingerprint": "$certificateFingerprint",
                "secretsJsonPath": "$secretsJsonPath",
                "tokenFilePath": "$tokenFilePath",
                "refreshSkewSeconds": $refreshSkewSeconds
              }
            }
            """.trimIndent()
        )
    }
}


