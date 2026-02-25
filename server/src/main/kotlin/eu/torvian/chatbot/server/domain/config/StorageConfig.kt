package eu.torvian.chatbot.server.domain.config

/**
 * Domain-level configuration for server-side data storage.
 *
 * @property baseApplicationPath The absolute or relative path to the base application directory
 *                               (parent of both the config/ and data/ directories).
 * @property dataDir The subdirectory name within [baseApplicationPath] for runtime data
 *                   (database, keystore, etc.). Typically "data".
 * @property databaseFilename The filename of the SQLite database within [dataPath]. Typically "chatbot.db".
 * @property keystoreFilename The filename of the SSL keystore within [dataPath]. Typically "keystore.jks".
 * @property logsDir Optional subdirectory name within [baseApplicationPath] for log files.
 */
data class StorageConfig(
    val baseApplicationPath: String,
    val dataDir: String,
    val databaseFilename: String,
    val keystoreFilename: String,
    val logsDir: String? = null
)

/**
 * The full path to the data directory.
 */
val StorageConfig.dataPath: String
    get() = "$baseApplicationPath/$dataDir"

/**
 * The full path to the database file.
 */
val StorageConfig.databasePath: String
    get() = "$dataPath/$databaseFilename"

/**
 * The full path to the keystore file.
 */
val StorageConfig.keystorePath: String
    get() = "$dataPath/$keystoreFilename"
