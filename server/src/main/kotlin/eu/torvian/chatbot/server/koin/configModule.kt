package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.config.AppConfiguration
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's settings.
 *
 * This module provides:
 * - The root application configuration.
 * - The application's database configuration.
 * - The application's encryption configuration.
 * - The application's JWT configuration.
 * - The application's account security policy.
 *
 * @param config The root application configuration to expose as a DI singleton.
 */
fun configModule(config: AppConfiguration) = module {
    single { config }
    single { config.database }
    single { config.encryption }
    single { config.jwt }
    single { config.accountSecurityMode }
    single { config.authPolicy }
}
