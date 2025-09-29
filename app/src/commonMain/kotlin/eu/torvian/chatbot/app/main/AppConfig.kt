package eu.torvian.chatbot.app.main


/**
 * Data class representing the application's configuration.
 *
 * @property serverUrl The base URL of the server.
 * @property baseUserDataStoragePath The base path for user data storage.
 * @property tokenStorageDir The directory for token storage.
 */
data class AppConfig(
    val serverUrl: String,
    val baseUserDataStoragePath: String,
    val tokenStorageDir: String
)