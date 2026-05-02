package eu.torvian.chatbot.worker.setup

import eu.torvian.chatbot.worker.config.PathResolver
import eu.torvian.chatbot.worker.config.StorageConfig
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPrivateKeyProviderTest {

    private val pathResolver = PathResolver()

    @Test
    fun `env var present returns env var value`() = runTest {
        val expectedKey = "-----BEGIN PRIVATE KEY-----\nenv-key\n-----END PRIVATE KEY-----"
        val provider = DefaultPrivateKeyProvider(
            envProvider = { name ->
                if (name == DefaultPrivateKeyProvider.PRIVATE_KEY_PEM_ENV_VAR) expectedKey else null
            }
        )

        val result = provider.loadPrivateKeyPem(
            Path.of("/tmp"),
            StorageConfig(secretsJsonPath = "./secrets.json", tokenFilePath = "./token.json")
        )

        assertTrue(result.isRight())
        assertEquals(expectedKey, result.getOrNull())
    }

    @Test
    fun `env var blank falls back to secrets file`() = runTest {
        val configDir = createTempDirectory("pkp-test")
        val secretsPath = configDir.resolve("secrets.json")
        secretsPath.writeText("""{"privateKeyPem":"file-key"}""")

        val provider = DefaultPrivateKeyProvider(
            envProvider = { _ -> "   " },
            pathResolver = pathResolver,
            secretsStore = FileSecretsStore()
        )

        val result = provider.loadPrivateKeyPem(
            configDir,
            StorageConfig(secretsJsonPath = "./secrets.json", tokenFilePath = "./token.json")
        )

        assertTrue(result.isRight())
        assertEquals("file-key", result.getOrNull())
    }

    @Test
    fun `env var absent falls back to secrets file`() = runTest {
        val configDir = createTempDirectory("pkp-test")
        val secretsPath = configDir.resolve("secrets.json")
        secretsPath.writeText("""{"privateKeyPem":"file-key"}""")

        val provider = DefaultPrivateKeyProvider(
            envProvider = { _ -> null },
            pathResolver = pathResolver,
            secretsStore = FileSecretsStore()
        )

        val result = provider.loadPrivateKeyPem(
            configDir,
            StorageConfig(secretsJsonPath = "./secrets.json", tokenFilePath = "./token.json")
        )

        assertTrue(result.isRight())
        assertEquals("file-key", result.getOrNull())
    }

    @Test
    fun `env var absent and secrets file missing returns SecretsReadFailed`() = runTest {
        val configDir = createTempDirectory("pkp-test")

        val provider = DefaultPrivateKeyProvider(
            envProvider = { _ -> null },
            pathResolver = pathResolver,
            secretsStore = FileSecretsStore()
        )

        val result = provider.loadPrivateKeyPem(
            configDir,
            StorageConfig(secretsJsonPath = "./secrets.json", tokenFilePath = "./token.json")
        )

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is PrivateKeyLoadError.SecretsReadFailed, "expected SecretsReadFailed but got $error")
    }

    @Test
    fun `env var trimmed when whitespace surrounded`() = runTest {
        val expectedKey = "trimmed-key"
        val provider = DefaultPrivateKeyProvider(
            envProvider = { name ->
                if (name == DefaultPrivateKeyProvider.PRIVATE_KEY_PEM_ENV_VAR) "  $expectedKey  " else null
            }
        )

        val result = provider.loadPrivateKeyPem(
            Path.of("/tmp"),
            StorageConfig(secretsJsonPath = "./secrets.json", tokenFilePath = "./token.json")
        )

        assertTrue(result.isRight())
        assertEquals(expectedKey, result.getOrNull())
    }
}
