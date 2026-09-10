package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseOptionalLong
import eu.torvian.chatbot.server.service.builtin.parseOptionalString
import eu.torvian.chatbot.server.service.builtin.parseRequiredString
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.CreateModelPresetError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `create_model_preset` server built-in tool.
 *
 * Creates a user-owned preset from the parsed input, reusing [CreateModelPresetRequest]. `name` is
 * required; `display_name`, `description`, `model_id`, and `model_settings_id` are optional and
 * fall back to the request DTO's defaults (`null`/empty description), so a preset may be created
 * without any LLM configuration and completed later — the service layer is deliberately permissive
 * and imposes no model-type restriction.
 *
 * Returns the created preset's full [ModelPresetDto] JSON instead of a one-line summary (the
 * `create_project` precedent): the DTO is small and flat, and the caller must learn the
 * server-generated id and timestamps to attach the preset in the same turn without an extra
 * `read_model_preset` round trip.
 *
 * @property modelPresetService User-scoped model-preset service used to create the preset.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class CreateModelPresetTool(
    private val modelPresetService: ModelPresetService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.CREATE_MODEL_PRESET_NAME

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
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_ID_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY
            ),
            validationErrors
        )
        val name = parseRequiredString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
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

        // name is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded. Omitted optional fields fall back to
        // the CreateModelPresetRequest defaults (no display name, empty description, no references).
        val request = CreateModelPresetRequest(
            name = name!!,
            displayName = displayName,
            description = description ?: "",
            modelId = modelId,
            modelSettingsId = modelSettingsId
        )
        val preset = modelPresetService.createPreset(context.userId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        encodeResult(json, preset).bind()
    }
}

/**
 * Maps a [CreateModelPresetError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * Every reference failure keeps the service's no-existence-leak convention: a model or settings
 * profile that is missing and one that is not accessible to the caller collapse into the same
 * message, and the mismatch variant names both model ids so the caller can re-point the pair.
 *
 * @receiver The typed create-preset failure.
 * @return The corresponding handler error.
 */
private fun CreateModelPresetError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is CreateModelPresetError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid model preset name: $reason")

    is CreateModelPresetError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A model preset named '$name' already exists for the current user."
        )

    is CreateModelPresetError.ModelNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_not_found",
            "Model $modelId not found or not accessible by the current user."
        )

    is CreateModelPresetError.SettingsNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_not_found",
            "Model settings profile $settingsId not found or not accessible by the current user."
        )

    is CreateModelPresetError.SettingsModelMismatch ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_model_mismatch",
            "Model settings profile $settingsId belongs to model $settingsModelId, not $presetModelId."
        )

    is CreateModelPresetError.OwnerInsertFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("owner_insert_failed", reason)
}
