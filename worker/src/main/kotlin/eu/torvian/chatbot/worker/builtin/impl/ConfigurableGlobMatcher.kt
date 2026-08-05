package eu.torvian.chatbot.worker.builtin.impl

import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.PatternSyntaxException

/**
 * A glob [PathMatcher] whose case sensitivity is configured via its constructor.
 *
 * The matcher translates Java-style glob syntax to a regular expression instead of delegating to
 * the platform's native glob matcher. This keeps matching behavior consistent across operating
 * systems, where the native matcher may impose its own case-sensitivity rules. The generated
 * expression uses [RegexOption.IGNORE_CASE] only when requested, so case-sensitive matching remains
 * possible on filesystems whose native matcher is inherently case-insensitive.
 *
 * Candidate paths are normalized to forward slashes before matching because the search tool uses
 * forward-slash relative paths on every platform. The original path is not modified or returned by
 * this class.
 *
 * @param pattern The Java-style glob pattern to match.
 * @param caseSensitive When true, matching distinguishes character case; when false, it ignores case.
 */
internal class ConfigurableGlobMatcher(
    pattern: String,
    caseSensitive: Boolean = false,
) : PathMatcher {

    /** The compiled regular expression representing [pattern]. */
    private val matcher: Regex = compileGlobPattern(pattern, caseSensitive)

    /**
     * Tests whether [path] is matched by the configured glob expression.
     *
     * @param path Candidate path to test.
     * @return True when the complete candidate path matches the glob expression.
     */
    override fun matches(path: Path): Boolean =
        matcher.matches(path.toString().replace('\\', '/'))

    /** Static implementation details for translating Java-style globs. */
    private companion object {
        /** Characters that must be escaped when copied into a regular expression. */
        private const val REGEX_META_CHARS = ".^$+{[]|()"

        /** Characters that have glob-specific meaning and may be escaped in a glob pattern. */
        private const val GLOB_META_CHARS = "\\*?[{"

        /** Sentinel returned when a look-ahead reaches the end of a glob pattern. */
        private const val END_OF_PATTERN: Char = '\u0000'

        /**
         * Converts a glob pattern into a compiled regular expression.
         *
         * The translation follows the JDK's Unix glob rules: `*` and `?` stay within one path
         * segment, `**` can cross separators, bracket expressions support ranges and negation,
         * and braces provide non-nested alternatives. Anchors ensure that the complete candidate
         * path is matched rather than an arbitrary substring.
         *
         * @param globPattern Glob expression supplied by the caller.
         * @param caseSensitive Whether the generated expression should distinguish case.
         * @return Compiled regular expression equivalent to the glob expression.
         * @throws PatternSyntaxException If the glob contains an invalid escape, class, range, or group.
         */
        private fun compileGlobPattern(globPattern: String, caseSensitive: Boolean): Regex {
            val regex = StringBuilder("^")
            var inGroup = false
            var index = 0

            while (index < globPattern.length) {
                var character = globPattern[index++]
                when (character) {
                    '\\' -> {
                        if (index == globPattern.length) {
                            throw PatternSyntaxException(
                                "No character to escape",
                                globPattern,
                                index - 1,
                            )
                        }
                        val escaped = globPattern[index++]
                        if (isGlobMeta(escaped) || isRegexMeta(escaped)) {
                            regex.append('\\')
                        }
                        regex.append(escaped)
                    }

                    '/' -> regex.append('/')

                    '[' -> {
                        // Exclude '/' from every class, matching Java's Unix glob behavior.
                        regex.append("[[^/]&&[")
                        if (nextCharacter(globPattern, index) == '^') {
                            // In Java glob syntax, '^' is a literal class member rather than negation.
                            regex.append("\\^")
                            index++
                        } else {
                            if (nextCharacter(globPattern, index) == '!') {
                                regex.append('^')
                                index++
                            }
                            // A leading '-' is a literal member of the class.
                            if (nextCharacter(globPattern, index) == '-') {
                                regex.append('-')
                                index++
                            }
                        }

                        var hasRangeStart = false
                        var lastRangeCharacter = END_OF_PATTERN
                        while (index < globPattern.length) {
                            character = globPattern[index++]
                            if (character == ']') break
                            if (character == '/') {
                                throw PatternSyntaxException(
                                    "Explicit 'name separator' in class",
                                    globPattern,
                                    index - 1,
                                )
                            }

                            if (character == '\\' || character == '[' ||
                                (character == '&' && nextCharacter(globPattern, index) == '&')
                            ) {
                                // Protect regex class syntax from glob class contents.
                                regex.append('\\')
                            }
                            regex.append(character)

                            if (character == '-') {
                                if (!hasRangeStart) {
                                    throw PatternSyntaxException("Invalid range", globPattern, index - 1)
                                }
                                val rangeEnd = nextCharacter(globPattern, index)
                                index++
                                if (rangeEnd == END_OF_PATTERN || rangeEnd == ']') {
                                    character = rangeEnd
                                    break
                                }
                                if (rangeEnd < lastRangeCharacter) {
                                    throw PatternSyntaxException("Invalid range", globPattern, index - 3)
                                }
                                regex.append(rangeEnd)
                                hasRangeStart = false
                            } else {
                                hasRangeStart = true
                                lastRangeCharacter = character
                            }
                        }

                        if (character != ']') {
                            throw PatternSyntaxException("Missing ']'", globPattern, index - 1)
                        }
                        regex.append("]]" )
                    }

                    '{' -> {
                        if (inGroup) {
                            throw PatternSyntaxException("Cannot nest groups", globPattern, index - 1)
                        }
                        regex.append("(?:(?:")
                        inGroup = true
                    }

                    '}' -> if (inGroup) {
                        regex.append("))")
                        inGroup = false
                    } else {
                        regex.append('}')
                    }

                    ',' -> if (inGroup) {
                        regex.append(")|(?:")
                    } else {
                        regex.append(',')
                    }

                    '*' -> if (nextCharacter(globPattern, index) == '*') {
                        // A double star is allowed to cross directory separators.
                        regex.append(".*")
                        index++
                    } else {
                        regex.append("[^/]*")
                    }

                    '?' -> regex.append("[^/]")

                    else -> {
                        if (isRegexMeta(character)) {
                            regex.append('\\')
                        }
                        regex.append(character)
                    }
                }
            }

            if (inGroup) {
                throw PatternSyntaxException("Missing '}'", globPattern, index - 1)
            }

            regex.append('$')
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return Regex(regex.toString(), options)
        }

        /**
         * Returns the character at [index], or the end sentinel when the pattern is exhausted.
         *
         * @param pattern Glob pattern being inspected.
         * @param index Zero-based look-ahead position.
         * @return Character at the requested position or [END_OF_PATTERN].
         */
        private fun nextCharacter(pattern: String, index: Int): Char =
            if (index < pattern.length) pattern[index] else END_OF_PATTERN

        /**
         * Determines whether [character] has special meaning in a regular expression.
         *
         * @param character Character being classified.
         * @return True when the character must be escaped outside a character class.
         */
        private fun isRegexMeta(character: Char): Boolean =
            character in REGEX_META_CHARS

        /**
         * Determines whether [character] has special meaning in a glob pattern.
         *
         * @param character Character being classified.
         * @return True when the character is a glob operator or glob escape target.
         */
        private fun isGlobMeta(character: Char): Boolean =
            character in GLOB_META_CHARS
    }
}
