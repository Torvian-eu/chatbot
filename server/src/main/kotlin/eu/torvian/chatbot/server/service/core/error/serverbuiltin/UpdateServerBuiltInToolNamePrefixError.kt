package eu.torvian.chatbot.server.service.core.error.serverbuiltin

import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError

/**
 * Logical errors that can occur while updating or deleting a user's server built-in tool name
 * prefix.
 */
sealed interface UpdateServerBuiltInToolNamePrefixError {

    /**
     * The requested prefix violated the tool-name prefix rules.
     *
     * @property reason Human-readable description of the validation failure.
     */
    data class InvalidInput(val reason: String) : UpdateServerBuiltInToolNamePrefixError

    /**
     * The preference row was written but renaming the user's persisted tool names failed.
     *
     * The caller wraps the preference write and the rename in one transaction, so this error
     * causes the whole operation (including the preference write) to roll back.
     *
     * @property seedError The underlying [SeedServerBuiltInToolsError] describing the rename failure.
     */
    data class RenameFailed(
        val seedError: SeedServerBuiltInToolsError,
    ) : UpdateServerBuiltInToolNamePrefixError
}
