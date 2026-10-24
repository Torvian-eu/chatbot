package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.worker.config.DefaultWorkerConfigLoader
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DefaultWorkerSetupManagerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val configLoader = DefaultWorkerConfigLoader()
    private val credentials = WorkerSetupCredentials(username = "setup-user", password = "setup-pass")

    /** Verifies that setup records the id the server assigned as the worker-id pin. */
    @Test
    fun `creates config and secrets when config is missing`() = runTest {
        val tempDir = createTempDirectory("worker-setup-test")
        val api = FakeWorkerSetupApi()
        val manager = createManager(api)

        try {
            val mergedConfig = configLoader.loadAppConfigDto(tempDir).getOrNull()
            assertTrue(mergedConfig != null)

            val result = manager.run(tempDir, mergedConfig, "https://localhost:8443/")
            assertTrue(result.isRight(), "setup failed: ${result.swap().getOrNull()}")

            assertEquals(credentials.username, api.loginUsername)
            assertEquals(credentials.password, api.loginPassword)
            assertNotNull(api.registerWorkerUid)
            assertNotNull(api.registerDisplayName)
            assertNotNull(api.registerCertificatePem)
            assertEquals(api.loginIssuedToken, api.logoutAccessToken)

            val applicationJson = json.parseToJsonElement(tempDir.resolve("application.json").readText()).jsonObject
            val workerJson = applicationJson["worker"]?.jsonObject
            val serverJson = workerJson?.get("server")?.jsonObject
            val identityJson = workerJson?.get("identity")?.jsonObject
            val storageJson = workerJson?.get("storage")?.jsonObject
            val authJson = workerJson?.get("auth")?.jsonObject
            val workspaceJson = workerJson?.get("workspace")?.jsonObject

            assertEquals("https://localhost:8443/", serverJson?.get("baseUrl")?.jsonPrimitive?.content)
            assertEquals(api.registerWorkerUid, identityJson?.get("uid")?.jsonPrimitive?.content)
            assertEquals("my-worker", identityJson?.get("displayName")?.jsonPrimitive?.content)
            assertEquals(
                api.assignedWorkerId.toString(),
                identityJson?.get("workerId")?.jsonPrimitive?.content,
                "setup must persist the id the server assigned"
            )
            assertNotNull(identityJson?.get("certificateFingerprint")?.jsonPrimitive?.content)
            assertTrue(identityJson["certificatePem"]?.jsonPrimitive?.content.orEmpty().contains("BEGIN CERTIFICATE"))
            assertEquals("./secrets.json", storageJson?.get("secretsJsonPath")?.jsonPrimitive?.content)
            assertEquals("./token.json", storageJson?.get("tokenFilePath")?.jsonPrimitive?.content)
            assertEquals("60", authJson?.get("refreshSkewSeconds")?.jsonPrimitive?.content)
            // Setup must preserve the workspace group so toDomain does not fail at runtime.
            assertNotNull(workspaceJson, "workspace group must be preserved by setup")
            assertEquals("../workspace", workspaceJson["path"]?.jsonPrimitive?.content)

            val setupJson = json.parseToJsonElement(tempDir.resolve("setup.json").readText()).jsonObject
            assertEquals("false", setupJson["setup"]?.jsonObject?.get("required")?.jsonPrimitive?.content)

            // Setup must create the workspace directory (resolved relative to the config dir).
            val workspaceDir = tempDir.resolve("../workspace").normalize()
            assertTrue(workspaceDir.exists() && workspaceDir.isDirectory(), "workspace should be created during setup")

            val secretsJson = json.parseToJsonElement(tempDir.resolve("secrets.json").readText()).jsonObject
            assertTrue(secretsJson["privateKeyPem"]?.jsonPrimitive?.content.orEmpty().contains("BEGIN"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uses existing server url and worker id from config when overrides are absent`() = runTest {
        val tempDir = createTempDirectory("worker-setup-test")
        val api = FakeWorkerSetupApi()
        tempDir.resolve("application.json").writeText(
            """
            {
              "worker": {
                "server": {
                  "baseUrl": "https://example.test/"
                },
                "identity": {
                  "uid": "existing-worker-uid",
                  "displayName": "existing-display",
                  "certificateFingerprint": "existing-fingerprint",
                  "certificatePem": "existing-pem"
                },
                "storage": {
                  "secretsJsonPath": "./secrets.json",
                  "tokenFilePath": "./token.json"
                },
                "auth": {
                  "refreshSkewSeconds": 60
                }
              }
            }
            """.trimIndent()
        )
        tempDir.resolve("setup.json").writeText("{}")
        tempDir.resolve("env-mapping.json").writeText("{}")

        try {
            val mergedConfig = configLoader.loadAppConfigDto(tempDir).getOrNull()
            assertTrue(mergedConfig != null)

            val result = createManager(api).run(tempDir, mergedConfig)
            assertTrue(result.isRight())
            assertEquals("existing-worker-uid", api.registerWorkerUid)
            assertEquals("existing-display", api.registerDisplayName)
            assertEquals(api.loginIssuedToken, api.logoutAccessToken)

            val applicationJson = json.parseToJsonElement(tempDir.resolve("application.json").readText()).jsonObject
            val workerJson = applicationJson["worker"]?.jsonObject
            val identityJson = workerJson?.get("identity")?.jsonObject
            assertEquals("existing-worker-uid", identityJson?.get("uid")?.jsonPrimitive?.content)
            assertEquals("existing-display", identityJson?.get("displayName")?.jsonPrimitive?.content)

            val setupJson = json.parseToJsonElement(tempDir.resolve("setup.json").readText()).jsonObject
            assertEquals("false", setupJson["setup"]?.jsonObject?.get("required")?.jsonPrimitive?.content)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that a failed registration leaves both config layers exactly as they were.
     *
     * The pin is the value the owner later checks against the server, so a partly written file would
     * make a worker that was never registered look provisioned.
     */
    @Test
    fun `a registration failure leaves the config untouched`() = runTest {
        val tempDir = createTempDirectory("worker-setup-test")
        val api = FakeWorkerSetupApi(registerShouldFail = true)
        val manager = createManager(api)

        try {
            val mergedConfig = configLoader.loadAppConfigDto(tempDir).getOrNull()
            assertTrue(mergedConfig != null)
            // Loading a fresh directory bootstraps the templates, so the comparison starts here.
            val applicationBefore = tempDir.resolve("application.json").readText()
            val setupBefore = tempDir.resolve("setup.json").readText()

            val result = manager.run(tempDir, mergedConfig, "https://localhost:8443/")

            assertTrue(result.isLeft(), "expected setup to fail")
            assertEquals(applicationBefore, tempDir.resolve("application.json").readText())
            assertEquals(setupBefore, tempDir.resolve("setup.json").readText())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that a hand-written command-isolation block survives the setup rewrite.
     *
     * The block is optional and nullable precisely so a rewrite of the fields setup owns does not
     * inject, drop or alter the operator's isolation choice.
     */
    @Test
    fun `preserves the command isolation block`() = runTest {
        val tempDir = createTempDirectory("worker-setup-test")
        val api = FakeWorkerSetupApi()
        tempDir.resolve("application.json").writeText(
            """
            {
              "worker": {
                "server": {
                  "baseUrl": "https://example.test/"
                },
                "commandIsolation": {
                  "enabled": false,
                  "user": "agent",
                  "helperPath": "/usr/local/bin/run-as-agent",
                  "environment": {
                    "allowPassthrough": ["PATH", "GOPATH"]
                  }
                }
              }
            }
            """.trimIndent()
        )
        tempDir.resolve("setup.json").writeText("{}")
        tempDir.resolve("env-mapping.json").writeText("{}")

        try {
            val mergedConfig = configLoader.loadAppConfigDto(tempDir).getOrNull()
            assertTrue(mergedConfig != null)

            val result = createManager(api).run(tempDir, mergedConfig)
            assertTrue(result.isRight(), "setup failed: ${result.swap().getOrNull()}")

            val applicationJson = json.parseToJsonElement(tempDir.resolve("application.json").readText()).jsonObject
            val isolationJson = applicationJson["worker"]?.jsonObject?.get("commandIsolation")?.jsonObject
            assertNotNull(isolationJson, "setup dropped the command isolation block")
            assertEquals(false, isolationJson["enabled"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("agent", isolationJson["user"]?.jsonPrimitive?.content)
            assertEquals("/usr/local/bin/run-as-agent", isolationJson["helperPath"]?.jsonPrimitive?.content)
            val allowPassthrough = isolationJson["environment"]?.jsonObject?.get("allowPassthrough")
            assertEquals(
                listOf("PATH", "GOPATH"),
                allowPassthrough?.jsonArray?.map { it.jsonPrimitive.content }
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writes secrets even when login fails`() = runTest {
        val tempDir = createTempDirectory("worker-setup-test")
        val api = FakeWorkerSetupApi(loginShouldFail = true)
        val manager = createManager(api)

        try {
            val mergedConfig = configLoader.loadAppConfigDto(tempDir).getOrNull()
            assertTrue(mergedConfig != null)

            val result = manager.run(tempDir, mergedConfig, "https://localhost:8443/")
            assertTrue(result.isLeft(), "expected setup to fail")

            val secretsJsonPath = tempDir.resolve("secrets.json")
            assertTrue(secretsJsonPath.toFile().exists(), "secrets.json should be written before login fails")

            val secretsJson = json.parseToJsonElement(secretsJsonPath.readText()).jsonObject
            assertTrue(secretsJson["privateKeyPem"]?.jsonPrimitive?.content.orEmpty().contains("BEGIN"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uses display name from provider when overridden`() = runTest {
        val tempDir = createTempDirectory("worker-setup-test")
        val api = FakeWorkerSetupApi()
        val customDisplayName = "custom-worker-name"
        val manager = createManager(
            api = api,
            displayNameProvider = object : WorkerSetupDisplayNameProvider {
                override suspend fun resolveDisplayName(defaultDisplayName: String) =
                    Either.Right(customDisplayName)
            }
        )

        try {
            val mergedConfig = configLoader.loadAppConfigDto(tempDir).getOrNull()
            assertTrue(mergedConfig != null)

            val result = manager.run(tempDir, mergedConfig, "https://localhost:8443/")
            assertTrue(result.isRight(), "setup failed: ${result.swap().getOrNull()}")

            assertEquals(customDisplayName, api.registerDisplayName)

            val applicationJson = json.parseToJsonElement(tempDir.resolve("application.json").readText()).jsonObject
            val workerJson = applicationJson["worker"]?.jsonObject
            val identityJson = workerJson?.get("identity")?.jsonObject
            assertEquals(customDisplayName, identityJson?.get("displayName")?.jsonPrimitive?.content)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun createManager(
        api: FakeWorkerSetupApi,
        displayNameProvider: WorkerSetupDisplayNameProvider = object : WorkerSetupDisplayNameProvider {
            override suspend fun resolveDisplayName(defaultDisplayName: String) =
                Either.Right(defaultDisplayName)
        },
        serverUrlProvider: WorkerSetupServerUrlProvider = object : WorkerSetupServerUrlProvider {
            override suspend fun resolveServerUrl(defaultServerUrl: String) =
                Either.Right(defaultServerUrl)
        },
        deviceIdProvider: WorkerSetupDeviceIdProvider = object : WorkerSetupDeviceIdProvider {
            override suspend fun resolveDeviceId() = Either.Right(null)
        }
    ): DefaultWorkerSetupManager {
        return DefaultWorkerSetupManager(
            configLoader = configLoader,
            secretsStore = FileSecretsStore(),
            credentialProvider = object : WorkerSetupCredentialProvider {
                override suspend fun resolveCredentials() = Either.Right(credentials)
            },
            displayNameProvider = displayNameProvider,
            serverUrlProvider = serverUrlProvider,
            deviceIdProvider = deviceIdProvider,
            setupApiFactory = { api }
        )
    }

    private class FakeWorkerSetupApi(
        private val loginShouldFail: Boolean = false,
        private val registerShouldFail: Boolean = false
    ) : WorkerSetupApi {
        var loginUsername: String? = null
        var loginPassword: String? = null
        var loginDeviceId: String? = null
        var registerWorkerUid: String? = null
        var registerDisplayName: String? = null
        var registerCertificatePem: String? = null
        var logoutAccessToken: String? = null
        val loginIssuedToken: String = "setup-login-token"

        /** Numeric worker id the fake registration reports as assigned. */
        val assignedWorkerId: Long = 42L

        override suspend fun login(username: String, password: String, deviceId: String): Either<WorkerSetupError, String> {
            loginUsername = username
            loginPassword = password
            loginDeviceId = deviceId
            if (loginShouldFail) {
                return Either.Left(WorkerSetupError.LoginFailed("invalid credentials"))
            }
            return Either.Right(loginIssuedToken)
        }

        override suspend fun registerWorker(
            accessToken: String,
            workerUid: String,
            displayName: String,
            certificatePem: String
        ): Either<WorkerSetupError, WorkerDto> {
            if (accessToken != loginIssuedToken) {
                return Either.Left(WorkerSetupError.WorkerRegistrationFailed("unexpected token"))
            }
            if (registerShouldFail) {
                return Either.Left(WorkerSetupError.WorkerRegistrationFailed("registration rejected"))
            }
            registerWorkerUid = workerUid
            registerDisplayName = displayName
            registerCertificatePem = certificatePem
            return Either.Right(
                WorkerDto(
                    id = assignedWorkerId,
                    workerUid = workerUid,
                    ownerUserId = 1L,
                    displayName = displayName,
                    certificateFingerprint = "setup-test-fingerprint",
                    allowedScopes = emptyList(),
                    createdAt = Instant.fromEpochMilliseconds(0),
                )
            )
        }

        override suspend fun logout(accessToken: String): Either<WorkerSetupError, Unit> {
            logoutAccessToken = accessToken
            return Either.Right(Unit)
        }
    }
}
