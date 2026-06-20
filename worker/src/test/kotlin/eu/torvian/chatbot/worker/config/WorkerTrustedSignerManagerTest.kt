package eu.torvian.chatbot.worker.config

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkerTrustedSignerManager].
 */
class WorkerTrustedSignerManagerTest {
    /**
     * Loader used by the manager under test so persisted files are read the same way production does.
     */
    private val configLoader = DefaultWorkerConfigLoader()

    /**
     * Service under test.
     */
    private val manager = WorkerTrustedSignerManager(configLoader)

    /**
     * Verifies that a new trusted signer is appended to an existing application config layer.
     */
    @Test
    fun `adds new signer to existing config`() {
        val configDir = createTempDirectory("worker-trusted-signer-test")
        writeApplicationJson(
            configDir,
            """
            {
              "worker": {
                "server": {
                  "baseUrl": "https://example.test/"
                },
                "identity": {
                  "uid": "worker-1",
                  "displayName": "worker-one",
                  "certificateFingerprint": "fingerprint-1",
                  "certificatePem": "pem-1"
                },
                "storage": {
                  "secretsJsonPath": "./secrets.json",
                  "tokenFilePath": "./token.json"
                },
                "auth": {
                  "refreshSkewSeconds": 60
                },
                "trustedSigners": []
              }
            }
            """.trimIndent()
        )

        try {
            val result = manager.addOrUpdateTrustedSigner(
                configDir = configDir,
                signerId = "signer-1",
                publicKeyBase64 = "AQID",
                permissionsCsv = "mcp:read,mcp:write"
            )

            assertTrue(result.isRight(), "trusted signer add failed: ${result.swap().getOrNull()}")
            assertEquals(false, result.getOrNull()?.replacedExisting)

            val savedConfig = loadApplicationConfig(configDir)
            val signer = savedConfig.worker?.trustedSigners?.single()
            assertNotNull(signer)
            assertEquals("signer-1", signer.signerId)
            assertEquals("AQID", signer.publicKeyBase64)
            assertEquals(listOf("mcp:read", "mcp:write"), signer.permissions)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that an existing signer entry is replaced in place rather than duplicated.
     */
    @Test
    fun `replaces existing signer with same signer id`() {
        val configDir = createTempDirectory("worker-trusted-signer-test")
        writeApplicationJson(
            configDir,
            """
            {
              "worker": {
                "trustedSigners": [
                  {
                    "signerId": "alpha",
                    "publicKeyBase64": "AQ==",
                    "permissions": ["a"]
                  },
                  {
                    "signerId": "target",
                    "publicKeyBase64": "Ag==",
                    "permissions": ["old"]
                  },
                  {
                    "signerId": "omega",
                    "publicKeyBase64": "Aw==",
                    "permissions": ["z"]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        try {
            val result = manager.addOrUpdateTrustedSigner(
                configDir = configDir,
                signerId = "target",
                publicKeyBase64 = "BA==",
                permissionsCsv = "new"
            )

            assertTrue(result.isRight(), "trusted signer replace failed: ${result.swap().getOrNull()}")
            assertEquals(true, result.getOrNull()?.replacedExisting)

            val signers = loadApplicationConfig(configDir).worker?.trustedSigners.orEmpty()
            assertEquals(3, signers.size)
            assertEquals("alpha", signers[0].signerId)
            assertEquals("target", signers[1].signerId)
            assertEquals("BA==", signers[1].publicKeyBase64)
            assertEquals(listOf("new"), signers[1].permissions)
            assertEquals("omega", signers[2].signerId)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that unrelated application-layer fields survive a trusted-signer patch unchanged.
     */
    @Test
    fun `preserves unrelated config fields`() {
        val configDir = createTempDirectory("worker-trusted-signer-test")
        writeApplicationJson(
            configDir,
            """
            {
              "setup": {
                "required": false
              },
              "worker": {
                "server": {
                  "baseUrl": "https://example.test/"
                },
                "identity": {
                  "uid": "worker-7",
                  "displayName": "worker-seven",
                  "certificateFingerprint": "fingerprint-7",
                  "certificatePem": "pem-7"
                },
                "storage": {
                  "secretsJsonPath": "./custom-secrets.json",
                  "tokenFilePath": "./custom-token.json"
                },
                "auth": {
                  "refreshSkewSeconds": 45
                },
                "trustedSigners": []
              }
            }
            """.trimIndent()
        )

        try {
            val result = manager.addOrUpdateTrustedSigner(
                configDir = configDir,
                signerId = "signer-9",
                publicKeyBase64 = "CQ==",
                permissionsCsv = null
            )

            assertTrue(result.isRight(), "trusted signer preserve-fields update failed: ${result.swap().getOrNull()}")

            val savedConfig = loadApplicationConfig(configDir)
            assertEquals(false, savedConfig.setup?.required)
            assertEquals("https://example.test/", savedConfig.worker?.server?.baseUrl)
            assertEquals("worker-7", savedConfig.worker?.identity?.uid)
            assertEquals("worker-seven", savedConfig.worker?.identity?.displayName)
            assertEquals("fingerprint-7", savedConfig.worker?.identity?.certificateFingerprint)
            assertEquals("pem-7", savedConfig.worker?.identity?.certificatePem)
            assertEquals("./custom-secrets.json", savedConfig.worker?.storage?.secretsJsonPath)
            assertEquals("./custom-token.json", savedConfig.worker?.storage?.tokenFilePath)
            assertEquals(45, savedConfig.worker?.auth?.refreshSkewSeconds)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that the manager creates the minimal worker structure when the application layer lacks it.
     */
    @Test
    fun `creates missing worker trusted signers structure when absent`() {
        val configDir = createTempDirectory("worker-trusted-signer-test")
        writeApplicationJson(
            configDir,
            """
            {
              "setup": {
                "required": false
              }
            }
            """.trimIndent()
        )

        try {
            val result = manager.addOrUpdateTrustedSigner(
                configDir = configDir,
                signerId = "signer-11",
                publicKeyBase64 = "Cw==",
                permissionsCsv = "bootstrap"
            )

            assertTrue(result.isRight(), "trusted signer minimal-structure update failed: ${result.swap().getOrNull()}")

            val savedConfig = loadApplicationConfig(configDir)
            assertEquals(false, savedConfig.setup?.required)
            val signers = savedConfig.worker?.trustedSigners.orEmpty()
            assertEquals(1, signers.size)
            assertEquals("signer-11", signers.single().signerId)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies trimming and deduplication rules for the CLI permissions input owned by the manager.
     */
    @Test
    fun `normalizes permissions`() {
        val configDir = createTempDirectory("worker-trusted-signer-test")
        writeApplicationJson(configDir, "{}")

        try {
            val result = manager.addOrUpdateTrustedSigner(
                configDir = configDir,
                signerId = " signer-15 ",
                publicKeyBase64 = " AQID ",
                permissionsCsv = " read, write ,read, , execute , write "
            )

            assertTrue(result.isRight(), "trusted signer normalize update failed: ${result.swap().getOrNull()}")

            val signer = loadApplicationConfig(configDir).worker?.trustedSigners?.single()
            assertNotNull(signer)
            assertEquals("signer-15", signer.signerId)
            assertEquals("AQID", signer.publicKeyBase64)
            assertEquals(listOf("read", "write", "execute"), signer.permissions)
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    /**
     * Loads the saved application-layer DTO for assertions.
     *
     * @param configDir Config directory that contains `application.json`.
     * @return Decoded application-layer DTO.
     */
    private fun loadApplicationConfig(configDir: java.nio.file.Path): AppConfigDto {
        return configLoader.loadLayerDto(
            configDir = configDir,
            fileName = DefaultWorkerConfigLoader.APPLICATION_FILE_NAME,
            optional = false
        ).fold(
            ifLeft = { error("Expected application config to load successfully: $it") },
            ifRight = { it }
        )
    }

    /**
     * Writes `application.json` test content into the provided config directory.
     *
     * @param configDir Config directory that will receive the file.
     * @param content Raw JSON content to persist.
     */
    private fun writeApplicationJson(configDir: java.nio.file.Path, content: String) {
        configDir.resolve(DefaultWorkerConfigLoader.APPLICATION_FILE_NAME).writeText(content)
    }
}