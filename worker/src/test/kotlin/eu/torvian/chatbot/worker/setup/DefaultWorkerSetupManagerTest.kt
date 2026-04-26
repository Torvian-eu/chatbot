package eu.torvian.chatbot.worker.setup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import eu.torvian.chatbot.worker.config.DefaultWorkerConfigLoader
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultWorkerSetupManagerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val configLoader = DefaultWorkerConfigLoader()
    private val credentials = WorkerSetupCredentials(username = "setup-user", password = "setup-pass")

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

            assertEquals("https://localhost:8443/", serverJson?.get("baseUrl")?.jsonPrimitive?.content)
            assertEquals(api.registerWorkerUid, identityJson?.get("uid")?.jsonPrimitive?.content)
            assertEquals("my-worker", identityJson?.get("displayName")?.jsonPrimitive?.content)
            assertNotNull(identityJson?.get("certificateFingerprint")?.jsonPrimitive?.content)
            assertTrue(identityJson["certificatePem"]?.jsonPrimitive?.content.orEmpty().contains("BEGIN CERTIFICATE"))
            assertEquals("./secrets.json", storageJson?.get("secretsJsonPath")?.jsonPrimitive?.content)
            assertEquals("./token.json", storageJson?.get("tokenFilePath")?.jsonPrimitive?.content)
            assertEquals("60", authJson?.get("refreshSkewSeconds")?.jsonPrimitive?.content)

            val setupJson = json.parseToJsonElement(tempDir.resolve("setup.json").readText()).jsonObject
            assertEquals("false", setupJson["setup"]?.jsonObject?.get("required")?.jsonPrimitive?.content)

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
                    arrow.core.Either.Right(customDisplayName)
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
                arrow.core.Either.Right(defaultDisplayName)
        }
    ): DefaultWorkerSetupManager {
        return DefaultWorkerSetupManager(
            configLoader = configLoader,
            secretsStore = FileSecretsStore(),
            credentialProvider = object : WorkerSetupCredentialProvider {
                override suspend fun resolveCredentials() = arrow.core.Either.Right(credentials)
            },
            displayNameProvider = displayNameProvider,
            setupApiFactory = { api }
        )
    }

    private class FakeWorkerSetupApi(
        private val loginShouldFail: Boolean = false
    ) : WorkerSetupApi {
        var loginUsername: String? = null
        var loginPassword: String? = null
        var registerWorkerUid: String? = null
        var registerDisplayName: String? = null
        var registerCertificatePem: String? = null
        var logoutAccessToken: String? = null
        val loginIssuedToken: String = "setup-login-token"

        override suspend fun login(username: String, password: String): arrow.core.Either<WorkerSetupError, String> {
            loginUsername = username
            loginPassword = password
            if (loginShouldFail) {
                return arrow.core.Either.Left(WorkerSetupError.LoginFailed("invalid credentials"))
            }
            return arrow.core.Either.Right(loginIssuedToken)
        }

        override suspend fun registerWorker(
            accessToken: String,
            workerUid: String,
            displayName: String,
            certificatePem: String
        ): arrow.core.Either<WorkerSetupError, Unit> {
            if (accessToken != loginIssuedToken) {
                return arrow.core.Either.Left(WorkerSetupError.WorkerRegistrationFailed("unexpected token"))
            }
            registerWorkerUid = workerUid
            registerDisplayName = displayName
            registerCertificatePem = certificatePem
            return arrow.core.Either.Right(Unit)
        }

        override suspend fun logout(accessToken: String): arrow.core.Either<WorkerSetupError, Unit> {
            logoutAccessToken = accessToken
            return arrow.core.Either.Right(Unit)
        }
    }
}


