package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.*
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.UpdateModelPresetError
import kotlinx.serialization.json.JsonObject

/**
 * `update_model_preset` server built-in tool.
 *
 * Implements PATCH semantics over the preset service's full-replacement update: `model_preset_id`
 * plus only the fields to change. The persisted preset is loaded through the ownership-checked
 * lookup and every provided field is merged over it, so a partial payload never wipes the preset's
 * state:
 * - omitted or explicitly-`null` fields preserve the persisted value
 *   ([parseOptionalString]/[parseOptionalLong] decode both to `null`);
 * - an explicit empty `description` clears the description (the service stores an empty string);
 * - an explicit empty `display_name` clears the display name to `null`;
 * - `model_id = 0` and `model_settings_id = 0` clear the respective reference, mirroring the
 *   `model_preset_id = 0` / `project_id = 0` "clear" sentinels of the agent-role and project
 *   tools. Preset ids are positive `AUTOINCREMENT` values, so `0` can never address a real row.
 *
 * The merged state is then applied through the existing full-replacement update, so the service's
 * name, accessibility, and model/settings agreement validation still runs on the *merged* state:
 * clearing only one half of the pair leaves a mismatch that the service rejects with a typed
 * `settings_model_mismatch`, telling the caller to re-point both references together.
 *
 * Returns a concise one-line summary of the completed operation (see [formatUpdatedModelPreset])
 * instead of the full preset JSON to keep the LLM context lean.
 *
 * @property modelPresetService User-scoped model-preset service used for the ownership-checked load
 *            and the merged update.
 */
class UpdateModelPresetTool(
    private val modelPresetService: ModelPresetService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME

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
            setOf(
                ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_ID_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY
            ),
            validationErrors
        )
        val presetId =
            parseRequiredLong(input, ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY, validationErrors)
        val name = parseOptionalString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val displayName =
            parseOptionalString(input, ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY, validationErrors)
        val description =
            parseOptionalString(input, ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY, validationErrors)
        val modelId = parseOptionalLong(input, ServerBuiltInToolCatalog.MODEL_ID_PROPERTY, validationErrors)
        val modelSettingsId =
            parseOptionalLong(input, ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // presetId is non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded.
        val persisted = modelPresetService.getPresetById(context.userId, presetId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Model preset $presetId not found or not accessible by the current user."
                )
            }
            .bind()

        // PATCH merge. `null` means "absent or explicitly null", i.e. preserve, because the parse
        // helpers cannot distinguish those two cases; clearing therefore needs the explicit
        // sentinels documented in the tool description ("" for the two strings, 0 for a reference).
        val request = UpdateModelPresetRequest(
            name = name ?: persisted.name,
            displayName = when (displayName) {
                null -> persisted.displayName
                "" -> null
                else -> displayName
            },
            description = description ?: persisted.description,
            modelId = when (modelId) {
                null -> persisted.modelId
                0L -> null
                else -> modelId
            },
            modelSettingsId = when (modelSettingsId) {
                null -> persisted.modelSettingsId
                0L -> null
                else -> modelSettingsId
            }
        )

        val updated = modelPresetService.updatePreset(context.userId, presetId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatUpdatedModelPreset(updated)
    }
}

/**
 * Maps an [UpdateModelPresetError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * The not-found variant surfaces as [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible]
 * (foreign and nonexistent presets collapse, no existence leak) while the remaining variants map
 * to the same readable codes `create_model_preset` uses, so the LLM sees one consistent vocabulary
 * across the write tools.
 *
 * @receiver The typed update-preset failure.
 * @return The corresponding handler error.
 */
private fun UpdateModelPresetError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is UpdateModelPresetError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Model preset $id not found or not accessible by the current user."
        )

    is UpdateModelPresetError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid model preset name: $reason")

    is UpdateModelPresetError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A model preset named '$name' already exists for the current user."
        )

    is UpdateModelPresetError.ModelNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_not_found",
            "Model $modelId not found or not accessible by the current user."
        )

    is UpdateModelPresetError.SettingsNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_not_found",
            "Model settings profile $settingsId not found or not accessible by the current user."
        )

    is UpdateModelPresetError.SettingsModelMismatch ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_model_mismatch",
            "Model settings profile $settingsId belongs to model $settingsModelId, not $presetModelId."
        )
}
