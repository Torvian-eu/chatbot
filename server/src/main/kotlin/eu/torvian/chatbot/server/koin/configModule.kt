package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.config.IpSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's settings.
 *
 * This module provides:
 * - The application's database configuration.
 * - The application's encryption configuration.
 * - The application's JWT configuration.
 * - The application's IP security policy.
 *
 * @param ipSecurityMode The IP security policy to expose as a DI singleton.
 */
fun configModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig,
    ipSecurityMode: IpSecurityMode = IpSecurityMode.DISABLED
) = module {
    single { databaseConfig }
    single { encryptionConfig }
    single { jwtConfig }
    single { ipSecurityMode }
}
