package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.EncryptionConfig

import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's settings.
 *
 * This module provides:
 * - The application's database configuration.
 * - The application's encryption configuration.
 */
fun configModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig
) = module {
    single { databaseConfig }
    single { encryptionConfig }
}
