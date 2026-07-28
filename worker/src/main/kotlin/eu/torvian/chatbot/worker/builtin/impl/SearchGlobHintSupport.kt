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
