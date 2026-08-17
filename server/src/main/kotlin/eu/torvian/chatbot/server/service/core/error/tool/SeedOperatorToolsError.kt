package eu.torvian.chatbot.server.service.core.error.tool

import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError

/**
 * Logical errors that can occur while seeding operator tool definitions for a user.
 *
 * The seeder composes two underlying failure sources: base tool creation ([ValidateToolError]) and
 * operator linkage persistence ([OperatorToolDefinitionError]). This sealed class aggregates them so
 * callers receive a single typed error instead of a raw [Throwable].
 */
sealed class SeedOperatorToolsError {

    /**
     * The base tool definition could not be created because it failed validation.
     *
     * @property error The underlying [ValidateToolError] describing the validation failure.
     */
    data class ToolCreationFailed(
        val error: ValidateToolError,
    ) : SeedOperatorToolsError()

    /**
     * The operator linkage could not be created.
     *
     * @property error The underlying [OperatorToolDefinitionError] (e.g. duplicate linkage or a
     * missing referenced entity).
     */
    data class LinkageFailed(
        val error: OperatorToolDefinitionError,
    ) : SeedOperatorToolsError()
}
