package eu.torvian.chatbot.app.utils.platform

/**
 * Utility functions for file path operations.
 * Handles common base path calculation, relative path calculation, and path validation.
 */
object FilePathUtils {

    /**
     * Checks if a given base path is a valid common ancestor for all provided file paths.
     * Normalizes all paths to use forward slashes for comparison.
     *
     * @param basePath The base path to validate
     * @param filePaths The list of absolute file paths to check
     * @return True if basePath is a valid common ancestor for all files
     */
    fun isValidCommonBasePath(basePath: String, filePaths: List<String>): Boolean {
        if (filePaths.isEmpty()) return true

        // Normalize all paths to use forward slashes
        val normalizedBase = basePath.replace('\\', '/').let {
            if (!it.endsWith('/')) "$it/" else it
        }

        return filePaths.all { filePath ->
            val normalized = filePath.replace('\\', '/')
            normalized.startsWith(normalizedBase) || normalized == normalizedBase.trimEnd('/')
        }
    }

    /**
     * Calculates the common base path from a list of file paths.
     * Finds the longest common path prefix that is a directory separator boundary.
     * Normalizes all paths to use forward slashes for consistent comparison.
     *
     * @param filePaths The list of absolute file paths
     * @return The common base path with forward slashes
     */
    fun calculateCommonBasePath(filePaths: List<String>): String {
        if (filePaths.isEmpty()) return ""
        if (filePaths.size == 1) {
            val normalized = filePaths[0].replace('\\', '/')
            return normalized.substringBeforeLast('/')
        }

        // Normalize all paths to use forward slashes
        val normalized = filePaths.map { it.replace('\\', '/') }
        val firstPath = normalized[0]
        val parts = firstPath.split('/')

        var commonPath = ""
        for (i in parts.indices) {
            val prefix = parts.subList(0, i + 1).joinToString("/")
            if (normalized.all { it.startsWith(prefix) }) {
                commonPath = prefix
            } else {
                break
            }
        }

        return commonPath.ifEmpty {
            // If no common path found, return the parent of first file
            firstPath.substringBeforeLast('/')
        }
    }

    /**
     * Calculates the relative path from a base path to an absolute path.
     * Normalizes all paths to use forward slashes for consistent calculation.
     *
     * @param basePath The base path to calculate from
     * @param absolutePath The absolute path to convert
     * @return The relative path using forward slashes, or just the filename if not under the base path
     */
    fun calculateRelativePath(basePath: String, absolutePath: String): String {
        val normalizedBase = basePath.replace('\\', '/')
        val normalizedAbsolute = absolutePath.replace('\\', '/')

        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            var relative = normalizedAbsolute.removePrefix(normalizedBase)
            if (relative.startsWith('/')) {
                relative = relative.removePrefix("/")
            }
            relative.ifEmpty { normalizedAbsolute.substringAfterLast('/') }
        } else {
            // If not under base path, just use the file name
            normalizedAbsolute.substringAfterLast('/')
        }
    }

    /**
     * Returns the parent directory of a given path string.
     * Handles both forward slashes and backslashes.
     *
     * Examples:
     * - `"/opt/chatbot/config"` → `"/opt/chatbot"`
     * - `"C:\chatbot\config"` → `"C:\chatbot"`
     * - `"config"` (no separator) → `"."`
     *
     * @param path The path whose parent to derive.
     * @return The parent directory string, or `"."` if there is no separator.
     */
    fun parentPath(path: String): String {
        val lastSeparatorIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (lastSeparatorIndex > 0) path.take(lastSeparatorIndex) else "."
    }

    /**
     * Splits a full file path into its directory (base path) and filename components.
     * Handles both forward slashes and backslashes.
     *
     * @param fullPath The full file path to split
     * @return A Pair where first is the base path (directory) and second is the filename
     */
    fun splitPathAndFilename(fullPath: String): Pair<String, String> {
        val lastSeparatorIndex = maxOf(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'))

        return if (lastSeparatorIndex >= 0) {
            val basePath = fullPath.take(lastSeparatorIndex)
            val fileName = fullPath.substring(lastSeparatorIndex + 1)
            Pair(basePath, fileName)
        } else {
            // No directory separator found (shouldn't happen in practice)
            Pair("", fullPath)
        }
    }
}

