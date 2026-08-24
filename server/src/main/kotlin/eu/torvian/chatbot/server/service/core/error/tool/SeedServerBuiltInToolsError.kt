package eu.torvian.chatbot.server.service.core.error.tool

import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError

/**
 * Logical errors that can occur while seeding server built-in tool definitions for a user.
 *
 * The seeder composes three underlying failure sources: base tool creation ([ValidateToolError]),
 * server built-in linkage persistence ([ServerBuiltInToolDefinitionError]), and pruning of stale
 * instances ([DeleteToolError]). This sealed class aggregates them so callers receive a single
 * typed error instead of a raw [Throwable].
 */
sealed class SeedServerBuiltInToolsError {

    /**
     * The base tool definition could not be created because it failed validation.
     *
     * @property error The underlying [ValidateToolError] describing the validation failure.
     */
    data class ToolCreationFailed(
        val error: ValidateToolError,
    ) : SeedServerBuiltInToolsError()

    /**
     * The server built-in linkage could not be created.
     *
     * @property error The underlying [ServerBuiltInToolDefinitionError] (e.g. duplicate linkage or a
     * missing referenced entity).
     */
    data class LinkageFailed(
        val error: ServerBuiltInToolDefinitionError,
    ) : SeedServerBuiltInToolsError()

    /**
     * A stale server built-in tool definition (its catalog spec no longer exists) could not be
     * pruned because the base tool definition could not be deleted.
     *
     * @property error The underlying [DeleteToolError] describing the deletion failure.
     */
    data class ToolDeletionFailed(
        val error: DeleteToolError,
    ) : SeedServerBuiltInToolsError()
}
