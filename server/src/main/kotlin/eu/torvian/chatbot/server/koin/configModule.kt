package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.security.JwtConfig

import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's settings.
 *
 * This module provides:
 * - The application's database configuration.
 * - The application's encryption configuration.
 * - The application's JWT configuration.
 */
fun configModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig
) = module {
    single { databaseConfig }
    single { encryptionConfig }
    single { jwtConfig }
}
