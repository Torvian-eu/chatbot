package eu.torvian.chatbot.worker.builtin.impl

import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks whether [pattern] appears to be a non-recursive top-level glob pattern.
 *
 * A pattern is considered non-recursive and top-level if it does not contain recursive operators (`**`)
 * and does not contain path separators (`/` or `\`).
 *
 * @param pattern The glob pattern to inspect.
 * @return True if the pattern is non-recursive and top-level.
 */
internal fun looksLikeNonRecursiveTopLevelPattern(pattern: String): Boolean {
    if ("**" in pattern) return false
    if ("/" in pattern || "\\" in pattern) return false
    return true
}

/**
 * Checks whether the starting directory [root] contains any subdirectories.
 *
 * Walks the directory tree rooted at [root] and returns true if any filesystem entry other than
 * [root] itself is a directory.
 *
 * @param root The starting directory path.
 * @return True if at least one subdirectory exists under [root].
 */
internal fun hasSubdirectories(root: Path): Boolean {
    return Files.isDirectory(root) && Files.walk(root).use { stream ->
        stream.anyMatch { it != root && Files.isDirectory(it) }
    }
}

/**
 * Produces a clean recursive suggestion pattern corresponding to a non-recursive top-level [pattern].
 *
 * Prefixes the pattern with `**` (handling leading wildcards cleanly to avoid triple asterisks)
 * without introducing path separators that would exclude the starting directory itself.
 *
 * @param pattern The original non-recursive glob pattern.
 * @return The suggested recursive glob pattern.
 */
internal fun toRecursiveHintPattern(pattern: String): String =
    when {
        "**" in pattern -> pattern
        pattern.startsWith("*") -> "**${pattern.removePrefix("*")}"
        else -> "**$pattern"
    }

/**
 * Checks whether [pattern] is an unintentional leading slash-star-star pattern that excludes the starting directory.
 *
 * A pattern is considered an unintentional leading pattern if it starts with the prefix,
 * does not start with recursive double asterisks after the prefix, and does not contain path separators (`/` or `\`).
 *
 * @param pattern The glob pattern to inspect.
 * @return True if the pattern has an unintentional leading prefix that can be flattened.
 */
internal fun isUnintentionalLeadingSlashStarStar(pattern: String): Boolean {
    val prefix = "**" + "/"
    if (!pattern.startsWith(prefix)) return false
    val rest = pattern.removePrefix(prefix)
    if (rest.startsWith("**")) return false
    if ('/' in rest || '\\' in rest) return false
    return true
}

/**
 * Transforms an unintentional leading slash-star-star glob pattern cleanly without producing triple asterisks.
 *
 * For example, a nested pattern with file extension becomes a top-level recursive pattern.
 * If the pattern is not an unintentional leading pattern, it is returned unchanged.
 *
 * @param pattern The glob pattern to transform.
 * @return The transformed glob pattern.
 */
internal fun fixLeadingSlashStarStar(pattern: String): String {
    if (!isUnintentionalLeadingSlashStarStar(pattern)) return pattern
    val prefix = "**" + "/"
    val rest = pattern.removePrefix(prefix)
    return if (rest.startsWith("*")) {
        "**" + rest.removePrefix("*")
    } else {
        "**$rest"
    }
}
