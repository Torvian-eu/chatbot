package eu.torvian.chatbot.app.config

import eu.torvian.chatbot.common.security.EncryptionConfig

/**
 * The root configuration object for the Chatbot Client application.
 *
 * This aggregate root holds all specialized configuration sections required for the
 * application's lifecycle and various services. All contained configuration objects
 * are strictly validated and guaranteed to be non-null when this class is instantiated.
 *
 * @property setupRequired Whether the client should perform initial setup steps on startup.
 * @property network Settings for network connectivity, primarily the server URL.
 * @property storage Settings for client-side data persistence (database, logs, user files).
 * @property encryption Settings for local data encryption and master keys.
 */
data class AppConfiguration(
    val setupRequired: Boolean,
    val network: NetworkConfig,
    val storage: StorageConfig,
    val encryption: EncryptionConfig
)