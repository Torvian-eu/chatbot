package eu.torvian.chatbot.server.service.core.error.tool

import eu.torvian.chatbot.server.data.dao.error.BuiltInToolDefinitionError

/**
 * Logical errors that can occur while seeding or renaming built-in worker tool definitions.
 *
 * The seeder composes two underlying failure sources: base tool creation
 * ([ValidateToolError]) and built-in linkage persistence ([BuiltInToolDefinitionError]). This
 * sealed class aggregates them so callers receive a single typed error instead of a raw
 * [Throwable].
 */
sealed class SeedBuiltInToolsError {
    /**
     * The base tool definition could not be created because it failed validation.
     *
     * @property error The underlying [ValidateToolError] describing the validation failure.
     */
    data class ToolCreationFailed(
        val error: ValidateToolError,
    ) : SeedBuiltInToolsError()

    /**
     * The built-in linkage could not be created or its public name could not be updated.
     *
     * @property error The underlying [BuiltInToolDefinitionError] (e.g. duplicate linkage or a
     * missing referenced entity).
     */
    data class LinkageFailed(
        val error: BuiltInToolDefinitionError,
    ) : SeedBuiltInToolsError()
}

