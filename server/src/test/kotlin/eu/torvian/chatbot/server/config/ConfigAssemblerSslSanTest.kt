package eu.torvian.chatbot.server.config

import eu.torvian.chatbot.server.domain.config.SslConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class ConfigAssemblerSslSanTest {

    @Test
    fun `toDomain uses default SAN values when ssl SAN config is omitted`() {
        val result = buildConfig(
            ssl = SslConfigDto(
                port = 8443,
                keystorePassword = "keystore-pass",
                keyAlias = "server-key",
                keyPassword = "key-pass"
            )
        ).toDomain(baseApplicationPath = "base")

        result.fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                val ssl = config.ssl ?: fail("Expected SSL config to be present")
                assertEquals(SslConfig.DEFAULT_SAN_DOMAINS, ssl.sanDomains)
                assertEquals(SslConfig.DEFAULT_SAN_IP_ADDRESSES, ssl.sanIpAddresses)
            }
        )
    }

    @Test
    fun `toDomain uses custom SAN values from ssl config`() {
        val customDomains = listOf("chatbot.internal", "api.example.com")
        val customIps = listOf("192.168.1.10", "10.0.2.2")

        val result = buildConfig(
            ssl = SslConfigDto(
                port = 8443,
                keystorePassword = "keystore-pass",
                keyAlias = "server-key",
                keyPassword = "key-pass",
                sanDomains = customDomains,
                sanIpAddresses = customIps
            )
        ).toDomain(baseApplicationPath = "base")

        result.fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                val ssl = config.ssl ?: fail("Expected SSL config to be present")
                assertEquals(customDomains, ssl.sanDomains)
                assertEquals(customIps, ssl.sanIpAddresses)
            }
        )
    }

    @Test
    fun `toDomain rejects invalid SAN IP values`() {
        val result = buildConfig(
            ssl = SslConfigDto(
                port = 8443,
                keystorePassword = "keystore-pass",
                keyAlias = "server-key",
                keyPassword = "key-pass",
                sanIpAddresses = listOf("not-an-ip")
            )
        ).toDomain(baseApplicationPath = "base")

        result.fold(
            ifLeft = { error ->
                val invalid = assertIs<ConfigError.ValidationError.InvalidValue>(error)
                assertEquals("ssl.sanIpAddresses[0]", invalid.path)
            },
            ifRight = { fail("Expected invalid SAN IP to fail validation") }
        )
    }

    private fun buildConfig(ssl: SslConfigDto) = AppConfigDto(
        setup = SetupConfigDto(required = false),
        storage = StorageConfigDto(
            dataDir = "data",
            databaseFilename = "chatbot.db",
            keystoreFilename = "keystore.jks",
            logsDir = "logs"
        ),
        network = NetworkConfigDto(
            host = "localhost",
            port = 8080,
            path = "",
            connectorType = "HTTPS"
        ),
        ssl = ssl,
        database = DatabaseConfigDto(
            vendor = "sqlite",
            type = "file"
        ),
        encryption = EncryptionConfigDto(
            keyVersion = 1,
            masterKeys = mapOf(1 to "dummy-master-key")
        ),
        jwt = JwtConfigDto(
            issuer = "chatbot-server",
            audience = "chatbot-users",
            realm = "chatbot-realm",
            secret = "jwt-secret",
            tokenExpirationMs = 60_000,
            refreshExpirationMs = 120_000
        )
    )
}

