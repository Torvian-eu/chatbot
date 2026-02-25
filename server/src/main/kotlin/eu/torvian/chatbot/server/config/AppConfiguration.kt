package eu.torvian.chatbot.server.config

import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.config.NetworkConfig
import eu.torvian.chatbot.server.domain.config.SslConfig
import eu.torvian.chatbot.server.domain.config.StorageConfig
import eu.torvian.chatbot.server.domain.security.JwtConfig

/**
 * The root configuration object for the Chatbot Server application.
 *
 * This aggregate root holds all specialized configuration sections required for the
 * application's lifecycle and various services.
 *
 * @property setupRequired Whether the server should perform initial secret generation on startup.
 * @property storage Settings for data storage paths (data directory, keystore filename).
 * @property network Settings for the network engine (host, port).
 * @property ssl Settings for SSL/TLS. Required when connectorType is HTTPS or HTTP_AND_HTTPS.
 * @property database Settings for the persistence layer (SQLite).
 * @property encryption Settings for data-at-rest encryption and master keys.
 * @property jwt Settings for authentication and token validation.
 */
data class AppConfiguration(
    val setupRequired: Boolean,
    val storage: StorageConfig,
    val network: NetworkConfig,
    val ssl: SslConfig?,
    val database: DatabaseConfig,
    val encryption: EncryptionConfig,
    val jwt: JwtConfig
)