package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import eu.torvian.chatbot.server.config.AppConfiguration
import eu.torvian.chatbot.server.domain.config.AccountSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.koin.configModule
import eu.torvian.chatbot.server.testutils.data.TestDefaults

/**
 * Provides a default test configuration for the chatbot application.
 *
 * This module configures the database to use an in-memory SQLite database
 * and provides default encryption settings for testing purposes.
 *
 * @param accountSecurityMode The account security policy to expose to authentication tests.
 */
fun defaultTestConfigModule(accountSecurityMode: AccountSecurityMode = AccountSecurityMode.DISABLED) = configModule(
    config = AppConfiguration(
        setupRequired = false,
        storage = TestDefaults.getDefaultStorageConfig(),
        network = TestDefaults.getDefaultNetworkConfig(),
        cors = TestDefaults.getDefaultCorsConfig(),
        ssl = null,
        database = TestDefaults.getDefaultDatabaseConfig(),
        encryption = TestDefaults.DEFAULT_ENCRYPTION_CONFIG,
        jwt = JwtConfig(secret = "test-secret-key-for-testing-purposes-only"),
        accountSecurityMode = accountSecurityMode,
        reverseProxy = TestDefaults.getDefaultReverseProxyConfig(),
        authPolicy = AccountValidationPolicy(
            passwordConfig = PasswordValidationConfig(),
            usernameConfig = UsernameValidationConfig()
        )
    )
)
