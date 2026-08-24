package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `list_model_settings` server built-in tool.
 *
 * Returns every settings profile accessible by the current user for one model the user can access.
 * The model must be accessible (`AccessMode.READ`) first; otherwise the handler collapses
 * not-found and not-accessible into one message to avoid id enumeration.
 *
 * @property llmModelService User-scoped model service used to verify model accessibility.
 * @property modelSettingsService User-scoped settings service used to load the accessible profiles.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListModelSettingsTool(
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_MODEL_SETTINGS_NAME

    /** Catalog spec for this tool: the single source of [name], [description], and [inputSchema]. */
    private val spec: ServerBuiltInToolCatalog.ServerBuiltInToolSpec =
        requireNotNull(ServerBuiltInToolCatalog.specFor(name)) {
            "Catalog must contain a spec for server built-in tool '$name'"
        }

    override val description: String get() = spec.description
    override val inputSchema: JsonObject get() = spec.inputSchema

    override suspend fun execute(
        userId: Long,
        input: JsonObject
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(input, setOf(ServerBuiltInToolCatalog.MODEL_ID_PROPERTY), validationErrors)
        val modelId = parseRequiredLong(input, ServerBuiltInToolCatalog.MODEL_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // modelId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        val accessibleModels = llmModelService.getAllAccessibleModels(userId, AccessMode.READ)
        ensure(!accessibleModels.none { it.id == modelId }) {
            ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                "Model $modelId not found or not accessible by the current user."
            )
        }
        val settings =
            modelSettingsService.getAccessibleSettingsByModelId(userId, modelId!!, AccessMode.READ)
        encodeResult(json, settings).bind()
    }
}
