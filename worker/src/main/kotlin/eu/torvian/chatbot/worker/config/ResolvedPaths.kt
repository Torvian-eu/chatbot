package eu.torvian.chatbot.worker.config

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Resolved absolute paths derived from a [StorageConfig] and a config directory.
 *
 * @property secretsPath Absolute path to the secrets JSON file.
 * @property tokenPath Absolute path to the token cache file.
 */
data class ResolvedPaths(
    val secretsPath: Path,
    val tokenPath: Path
)

/**
 * Resolves configured relative paths against the worker config directory.
 */
class PathResolver {
    /**
     * Resolves [StorageConfig] paths into absolute [ResolvedPaths].
     *
     * @param configDir The worker configuration directory used as a base for relative paths.
     * @param storage Storage configuration containing potentially relative paths.
     * @return [ResolvedPaths] with both entries normalised and absolute.
     */
    fun resolve(configDir: Path, storage: StorageConfig): ResolvedPaths {
        return ResolvedPaths(
            secretsPath = resolvePath(configDir, storage.secretsJsonPath),
            tokenPath = resolvePath(configDir, storage.tokenFilePath)
        )
    }

    /**
     * Resolves a single configured path against the worker config directory.
     *
     * @param configDir The worker configuration directory used as a base for relative paths.
     * @param configuredPath A potentially relative file path string.
     * @return Normalised absolute [Path].
     */
    fun resolvePath(configDir: Path, configuredPath: String): Path {
        val normalizedConfigDir = configDir.toAbsolutePath().normalize()
        val targetPath = Path(configuredPath)
        return normalizedConfigDir.resolve(targetPath).normalize()
    }
}
