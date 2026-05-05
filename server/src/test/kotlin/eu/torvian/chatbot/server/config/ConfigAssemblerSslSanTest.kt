package eu.torvian.chatbot.server.config

import eu.torvian.chatbot.server.domain.config.SslConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

class ConfigAssemblerSslSanTest {

    @Test
    fun `toDomain parses allowed CORS origins`() {
        val result = buildConfig(
            ssl = defaultSsl(),
            cors = CorsConfigDto(
                allowedOrigins = listOf(
                    "https://chatbot-client-demo.torvian.eu",
                    "http://localhost:3000"
                )
            )
        ).toDomain(baseApplicationPath = "base")

        result.fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                assertEquals(2, config.cors.allowedOrigins.size)
                assertEquals("https", config.cors.allowedOrigins[0].scheme)
                assertEquals("chatbot-client-demo.torvian.eu", config.cors.allowedOrigins[0].host)
                assertNull(config.cors.allowedOrigins[0].port)
                assertEquals("localhost", config.cors.allowedOrigins[1].host)
                assertEquals(3000, config.cors.allowedOrigins[1].port)
            }
        )
    }

    @Test
    fun `toDomain rejects CORS origins with a path`() {
        val result = buildConfig(
            ssl = defaultSsl(),
            cors = CorsConfigDto(
                allowedOrigins = listOf("https://example.com/api")
            )
        ).toDomain(baseApplicationPath = "base")

        result.fold(
            ifLeft = { error ->
                val invalid = assertIs<ConfigError.ValidationError.InvalidValue>(error)
                assertEquals("cors.allowedOrigins[0]", invalid.path)
            },
            ifRight = { fail("Expected CORS origin with path to fail validation") }
        )
    }

    @Test
    fun `toDomain rejects CORS origins with unsupported scheme`() {
        val result = buildConfig(
            ssl = defaultSsl(),
            cors = CorsConfigDto(
                allowedOrigins = listOf("ftp://example.com")
            )
        ).toDomain(baseApplicationPath = "base")

        result.fold(
            ifLeft = { error ->
                val invalid = assertIs<ConfigError.ValidationError.InvalidValue>(error)
                assertEquals("cors.allowedOrigins[0]", invalid.path)
            },
            ifRight = { fail("Expected CORS origin with unsupported scheme to fail validation") }
        )
    }

    @Test
    fun `toDomain uses default SAN values when ssl SAN config is omitted`() {
        val result = buildConfig(ssl = defaultSsl()).toDomain(baseApplicationPath = "base")

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

    private fun defaultSsl() = SslConfigDto(
        port = 8443,
        keystorePassword = "keystore-pass",
        keyAlias = "server-key",
        keyPassword = "key-pass"
    )

    private fun buildConfig(
        ssl: SslConfigDto,
        cors: CorsConfigDto? = null
    ) = AppConfigDto(
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
        cors = cors,
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
            userAudience = "chatbot-users",
            workerAudience = "chatbot-workers",
            realm = "chatbot-realm",
            secret = "jwt-secret",
            tokenExpirationMs = 60_000,
            refreshExpirationMs = 120_000
        ),
        reverseProxy = ReverseProxyConfigDto(
            enabled = false,
            proxyCount = 1,
            useXForwardedHeaders = true,
            useForwardedHeaders = false
        )
    )
}
