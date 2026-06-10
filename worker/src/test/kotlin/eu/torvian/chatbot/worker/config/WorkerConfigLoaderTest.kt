package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.either
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkerConfigLoaderTest {

    private val loader = DefaultWorkerConfigLoader()

    @Test
    fun `loads valid worker config successfully`() {
        val result = loadConfigWithOverrides(
            serverBaseUrl = "https://example.test",
            workerUid = "worker-7",
            certificateFingerprint = "fingerprint-1",
            certificatePem = "pem-1",
            secretsJsonPath = "./secrets.json",
            tokenFilePath = "./token.json",
            refreshSkewSeconds = 75
        )

        assertTrue(result.isRight())
        val config = result.getOrNull()
        assertEquals("https://example.test", config?.worker?.server?.baseUrl)
        assertEquals("worker-7", config?.worker?.identity?.uid)
        assertEquals("my-worker", config?.worker?.identity?.displayName)
        assertEquals("fingerprint-1", config?.worker?.identity?.certificateFingerprint)
        assertEquals("pem-1", config?.worker?.identity?.certificatePem)
        assertEquals("./secrets.json", config?.worker?.storage?.secretsJsonPath)
        assertEquals("./token.json", config?.worker?.storage?.tokenFilePath)
        assertEquals(75, config?.worker?.auth?.refreshSkewSeconds)
        assertEquals(emptyList(), config?.worker?.trustedSigners)
    }

    /**
     * Verifies that populated trust-store entries are accepted and their public keys are decoded once.
     */
    @Test
    fun `loads trusted signers and decodes Base64 public keys`() {
        val result = loadConfigWithOverrides(
            trustedSignersJson = """
                [
                  {
                    "signerId": "device-1",
                    "publicKeyBase64": "ChD/",
                    "permissions": ["mcp:write"]
                  }
                ]
            """.trimIndent()
        )

        assertTrue(result.isRight())
        val signer = result.getOrNull()?.worker?.trustedSigners?.single()
        assertEquals("device-1", signer?.signerId)
        assertContentEquals(byteArrayOf(0x0A, 0x10, 0xFF.toByte()), signer?.publicKey)
        assertEquals(listOf("mcp:write"), signer?.permissions)
    }

    /**
     * Verifies that domain equality treats decoded public keys as value data rather than array identities.
     */
    @Test
    fun `trusted signer equality compares public key contents`() {
        val first = TrustedSigner("device-1", byteArrayOf(0x0A, 0x10), listOf("mcp:write"))
        val sameContent = TrustedSigner("device-1", byteArrayOf(0x0A, 0x10), listOf("mcp:write"))
        val differentKey = TrustedSigner("device-1", byteArrayOf(0x0A, 0x11), listOf("mcp:write"))

        assertEquals(first, sameContent)
        assertEquals(first.hashCode(), sameContent.hashCode())
        assertNotEquals(first, differentKey)
    }

    /**
     * Verifies that malformed trust-store key material fails during configuration assembly.
     */
    @Test
    fun `rejects invalid trusted signer public key Base64`() {
        val result = loadConfigWithOverrides(
            trustedSignersJson = """
                [
                  {
                    "signerId": "device-1",
                    "publicKeyBase64": "not-base64!",
                    "permissions": []
                  }
                ]
            """.trimIndent()
        )

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.trustedSigners[0].publicKeyBase64"))
        assertTrue(error.description.contains("valid Base64"))
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
                "server": {
                  "baseUrl": "https://example.test"
                }
                "identity": {
                  "uid": "worker-7"
                }
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
    fun `rejects blank server baseUrl`() {
        val result = loadConfigWithOverrides(serverBaseUrl = "   ")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.server.baseUrl must not be blank"))
    }

    @Test
    fun `rejects blank worker uid`() {
        val result = loadConfigWithOverrides(workerUid = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.identity.uid must not be blank"))
    }

    @Test
    fun `rejects malformed or unsupported server baseUrl`() {
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
        assertTrue(error.description.contains("worker.auth.refreshSkewSeconds must be >= 0"))
    }

    @Test
    fun `rejects blank certificate fingerprint`() {
        val result = loadConfigWithOverrides(certificateFingerprint = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.identity.certificateFingerprint must not be blank"))
    }

    @Test
    fun `rejects blank certificate pem`() {
        val result = loadConfigWithOverrides(certificatePem = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.identity.certificatePem must not be blank"))
    }

    @Test
    fun `rejects blank secrets json path`() {
        val result = loadConfigWithOverrides(secretsJsonPath = "")

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as? WorkerConfigError.ConfigInvalid
        assertTrue(error != null)
        assertTrue(error.description.contains("worker.storage.secretsJsonPath must not be blank"))
    }

    @Test
    fun `setup layer overrides base layer`() {
        val configDir = createTempDirectory("worker-config-test")
        writeBaseConfig(configDir, workerUid = "worker-base", serverBaseUrl = "https://base.test")
        configDir.resolve("setup.json").writeText(
            """
            {
              "worker": {
                "identity": {
                  "uid": "worker-setup"
                }
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
            assertEquals("worker-setup", result.getOrNull()?.worker?.identity?.uid)
            assertEquals("https://base.test", result.getOrNull()?.worker?.server?.baseUrl)
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
                "server": {
                  "baseUrl": "https://setup.test"
                }
              }
            }
            """.trimIndent()
        )
        configDir.resolve("env-mapping.json").writeText(
            """
            {
              "worker": {
                "server": {
                  "baseUrl": "WORKER_SERVER_BASE_URL"
                }
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
            assertEquals("https://env.test", result.getOrNull()?.worker?.server?.baseUrl)
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
                "identity": {
                  "uid": "WORKER_UID"
                }
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
            assertEquals("worker-base", result.getOrNull()?.worker?.identity?.uid)
            assertEquals("https://base.test", result.getOrNull()?.worker?.server?.baseUrl)
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
        assertEquals(Path("./config"), fromDefault)
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
            assertEquals("worker-root", config?.worker?.identity?.uid)
            assertEquals("https://root.test", config?.worker?.server?.baseUrl)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    /**
     * Writes a temporary config using defaults plus selected overrides, then loads the strict domain model.
     *
     * @param serverBaseUrl Server URL to place in the base config.
     * @param workerUid Worker identity UID to place in the base config.
     * @param certificateFingerprint Certificate fingerprint to place in the base config.
     * @param certificatePem Certificate PEM to place in the base config.
     * @param secretsJsonPath Secrets file path to place in the base config.
     * @param tokenFilePath Token file path to place in the base config.
     * @param refreshSkewSeconds Token refresh skew to place in the base config.
     * @param trustedSignersJson Raw JSON array to place in `worker.trustedSigners`.
     * @return Either a logical configuration error or the assembled domain configuration.
     */
    private fun loadConfigWithOverrides(
        serverBaseUrl: String = "https://example.test",
        workerUid: String = "worker-7",
        certificateFingerprint: String = "fp",
        certificatePem: String = "pem",
        secretsJsonPath: String = "./secrets.json",
        tokenFilePath: String = "./token.json",
        refreshSkewSeconds: Long = 60,
        trustedSignersJson: String = "[]"
    ): Either<WorkerConfigError, Configuration> {
        val tempDir = createTempDirectory("worker-config-test")
        writeBaseConfig(
            tempDir,
            serverBaseUrl = serverBaseUrl,
            workerUid = workerUid,
            certificateFingerprint = certificateFingerprint,
            certificatePem = certificatePem,
            secretsJsonPath = secretsJsonPath,
            tokenFilePath = tokenFilePath,
            refreshSkewSeconds = refreshSkewSeconds,
            trustedSignersJson = trustedSignersJson
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

    /**
     * Writes a base worker application config file for config-loader tests.
     *
     * @param configDir Directory that receives `application.json`.
     * @param serverBaseUrl Server URL to write.
     * @param workerUid Worker identity UID to write.
     * @param displayName Worker display name to write.
     * @param certificateFingerprint Certificate fingerprint to write.
     * @param certificatePem Certificate PEM to write.
     * @param secretsJsonPath Secrets file path to write.
     * @param tokenFilePath Token file path to write.
     * @param refreshSkewSeconds Token refresh skew to write.
     * @param trustedSignersJson Raw JSON array to write as `worker.trustedSigners`.
     */
    private fun writeBaseConfig(
        configDir: java.nio.file.Path,
        serverBaseUrl: String,
        workerUid: String,
        displayName: String = "my-worker",
        certificateFingerprint: String = "fp",
        certificatePem: String = "pem",
        secretsJsonPath: String = "./secrets.json",
        tokenFilePath: String = "./token.json",
        refreshSkewSeconds: Long = 60,
        trustedSignersJson: String = "[]"
    ) {
        configDir.resolve("application.json").writeText(
            """
            {
              "worker": {
                "server": {
                  "baseUrl": "$serverBaseUrl"
                },
                "identity": {
                  "uid": "$workerUid",
                  "displayName": "$displayName",
                  "certificateFingerprint": "$certificateFingerprint",
                  "certificatePem": "$certificatePem"
                },
                "storage": {
                  "secretsJsonPath": "$secretsJsonPath",
                  "tokenFilePath": "$tokenFilePath"
                },
                "auth": {
                  "refreshSkewSeconds": $refreshSkewSeconds
                },
                "trustedSigners": $trustedSignersJson
              }
            }
            """.trimIndent()
        )
    }
}


