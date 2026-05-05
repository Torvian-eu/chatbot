package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.domain.config.IpSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.koin.configModule
import eu.torvian.chatbot.server.testutils.data.TestDefaults

/**
 * Provides a default test configuration for the chatbot application.
 *
 * This module configures the database to use an in-memory SQLite database
 * and provides default encryption settings for testing purposes.
 *
 * @param ipSecurityMode The IP security policy to expose to authentication tests.
 */
fun defaultTestConfigModule(ipSecurityMode: IpSecurityMode = IpSecurityMode.DISABLED) = configModule(
    databaseConfig = TestDefaults.getDefaultDatabaseConfig(),
    encryptionConfig = TestDefaults.DEFAULT_ENCRYPTION_CONFIG,
    jwtConfig = JwtConfig(secret = "test-secret-key-for-testing-purposes-only"),
    ipSecurityMode = ipSecurityMode
)
