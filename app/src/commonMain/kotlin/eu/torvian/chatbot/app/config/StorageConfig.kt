package eu.torvian.chatbot.app.config

import kotlinx.io.files.Path

/**
 * Domain-level configuration for client-side data storage.
 *
 * @property baseApplicationPath The absolute [Path] to the base application directory (parent of both
 *                              config/ and data/ directories). This is either the portable installation
 *                              directory (if config/ exists in CWD) or the OS-specific user data directory.
 * @property dataDir The subdirectory name within [baseApplicationPath] for user data (database, logs, etc.).
 *                   This is typically "data".
 * @property tokenStorageDir The subdirectory within the data directory for storing authentication tokens.
 * @property certificateStorageDir The subdirectory within the data directory for storing certificates.
 */
data class StorageConfig(
    val baseApplicationPath: Path,
    val dataDir: String,
    val tokenStorageDir: String,
    val certificateStorageDir: String
)

/**
 * Extension property to get the full path to the data directory.
 *
 * @return The absolute path to the data directory (baseApplicationPath / dataDir).
 */
val StorageConfig.dataPath: Path
    get() = Path(baseApplicationPath, dataDir)
