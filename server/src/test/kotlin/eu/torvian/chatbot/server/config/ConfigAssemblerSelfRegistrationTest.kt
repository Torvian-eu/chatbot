package eu.torvian.chatbot.server.config

import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Tests for parsing and layered merging of the self-registration flag in the auth policy block.
 *
 * The flag is optional in configuration: an absent value must assemble to disabled so existing
 * configuration files keep loading with self-registration off by default.
 */
class ConfigAssemblerSelfRegistrationTest {

    @Test
    fun `toDomain maps absent selfRegistrationEnabled to false`() {
        buildConfig(authPolicy = defaultAuthPolicy()).toDomain(baseApplicationPath = "base").fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                assertEquals(false, config.authPolicy.selfRegistrationEnabled)
            }
        )
    }

    @Test
    fun `toDomain parses explicit selfRegistrationEnabled true`() {
        val authPolicy = defaultAuthPolicy().copy(selfRegistrationEnabled = true)

        buildConfig(authPolicy = authPolicy).toDomain(baseApplicationPath = "base").fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                assertEquals(true, config.authPolicy.selfRegistrationEnabled)
            }
        )
    }

    @Test
    fun `merge lets overlay selfRegistrationEnabled win in both directions`() {
        val enabledOverDisabled = buildConfig(authPolicy = defaultAuthPolicy().copy(selfRegistrationEnabled = false))
            .merge(AppConfigDto(authPolicy = AuthPolicyDto(selfRegistrationEnabled = true)))

        enabledOverDisabled.toDomain(baseApplicationPath = "base").fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                assertEquals(true, config.authPolicy.selfRegistrationEnabled)
            }
        )

        val disabledOverEnabled = buildConfig(authPolicy = defaultAuthPolicy().copy(selfRegistrationEnabled = true))
            .merge(AppConfigDto(authPolicy = AuthPolicyDto(selfRegistrationEnabled = false)))

        disabledOverEnabled.toDomain(baseApplicationPath = "base").fold(
            ifLeft = { fail("Expected valid config, got error: $it") },
            ifRight = { config ->
                assertEquals(false, config.authPolicy.selfRegistrationEnabled)
            }
        )
    }

    @Test
    fun `merge overlay setting only the flag preserves other authPolicy fields`() {
        val base = buildConfig(
            authPolicy = AuthPolicyDto(
                passwordConfig = PasswordValidationConfig(minLength = 12),
                usernameConfig = UsernameValidationConfig(minLength = 5),
                maxFailedAttempts = 7,
                lockoutWindowMinutes = 9
            )
        )

        base.merge(AppConfigDto(authPolicy = AuthPolicyDto(selfRegistrationEnabled = true)))
            .toDomain(baseApplicationPath = "base").fold(
                ifLeft = { fail("Expected valid config, got error: $it") },
                ifRight = { config ->
                    assertEquals(true, config.authPolicy.selfRegistrationEnabled)
                    assertEquals(12, config.authPolicy.passwordConfig.minLength)
                    assertEquals(5, config.authPolicy.usernameConfig.minLength)
                    assertEquals(7, config.authPolicy.maxFailedAttempts)
                    assertEquals(9, config.authPolicy.lockoutWindowMinutes)
                }
            )
    }

    /**
     * Auth policy DTO with all sibling fields set and the self-registration flag absent.
     */
    private fun defaultAuthPolicy() = AuthPolicyDto(
        passwordConfig = PasswordValidationConfig(),
        usernameConfig = UsernameValidationConfig(),
        maxFailedAttempts = 10,
        lockoutWindowMinutes = 5
    )

    /**
     * Minimal valid application config DTO carrying the given auth policy block.
     */
    private fun buildConfig(authPolicy: AuthPolicyDto) = AppConfigDto(
        serverUrl = "http://localhost:8080",
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
        ssl = SslConfigDto(
            port = 8443,
            keystorePassword = "keystore-pass",
            keyAlias = "server-key",
            keyPassword = "key-pass"
        ),
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
        email = EmailConfigDto(
            provider = "log",
            fromAddress = "noreply@chatbot.test"
        ),
        reverseProxy = ReverseProxyConfigDto(
            enabled = false,
            proxyCount = 1,
            useXForwardedHeaders = true,
            useForwardedHeaders = false
        ),
        accountSecurityMode = "DISABLED",
        authPolicy = authPolicy
    )
}
