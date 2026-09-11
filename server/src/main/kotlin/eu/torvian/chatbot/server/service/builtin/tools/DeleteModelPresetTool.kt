package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.DeleteModelPresetError
import kotlinx.serialization.json.JsonObject

/**
 * `delete_model_preset` server built-in tool.
 *
 * Deletes the ownership-checked preset with the given id. The preset service collapses foreign and
 * nonexistent presets into a single not-found outcome, which this tool maps to
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] so it never leaks the existence of
 * another user's preset (id-enumeration guard).
 *
 * Deleting is non-destructive for the roles bound to the preset: `agent_roles.model_preset_id` is
 * nulled (`ON DELETE SET NULL`), so those roles survive and merely become non-sendable until
 * another preset is attached. That is the approved behaviour and therefore not reported as an
 * error. Returns a concise one-line summary of the completed operation (see
 * [formatDeletedModelPreset]).
 *
 * @property modelPresetService User-scoped model-preset service used to delete the preset.
 */
class DeleteModelPresetTool(
    private val modelPresetService: ModelPresetService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.DELETE_MODEL_PRESET_NAME

    /** Catalog spec for this tool: the single source of [name], [description], and [inputSchema]. */
    private val spec: ServerBuiltInToolCatalog.ServerBuiltInToolSpec =
        requireNotNull(ServerBuiltInToolCatalog.specFor(name)) {
            "Catalog must contain a spec for server built-in tool '$name'"
        }

    override val description: String get() = spec.description
    override val inputSchema: JsonObject get() = spec.inputSchema

    override suspend fun execute(
        input: JsonObject,
        context: ToolCallExecutionContext
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY),
            validationErrors
        )
        val presetId =
            parseRequiredLong(input, ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }
        // presetId is non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded.
        modelPresetService.deletePreset(context.userId, presetId!!)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatDeletedModelPreset(presetId)
    }
}

/**
 * Maps a [DeleteModelPresetError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * The only delete failure is not-found/not-accessible, surfaced as
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] (foreign and nonexistent presets
 * collapse, no existence leak).
 *
 * @receiver The typed delete-preset failure.
 * @return The corresponding handler error.
 */
private fun DeleteModelPresetError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is DeleteModelPresetError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Model preset $id not found or not accessible by the current user."
        )
}
