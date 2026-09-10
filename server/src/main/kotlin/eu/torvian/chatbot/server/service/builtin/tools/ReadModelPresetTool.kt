package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.ModelPresetService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `read_model_preset` server built-in tool.
 *
 * Returns the ownership-checked [ModelPresetDto] for one preset id. The preset service collapses a
 * foreign preset into the same not-found outcome as a nonexistent one, and this tool maps that
 * outcome to a single message so it never leaks the existence of another user's preset
 * (id-enumeration guard).
 *
 * @property modelPresetService User-scoped model-preset service used for the ownership-checked lookup.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ReadModelPresetTool(
    private val modelPresetService: ModelPresetService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.READ_MODEL_PRESET_NAME

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
        val preset = modelPresetService.getPresetById(context.userId, presetId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Model preset $presetId not found or not accessible by the current user."
                )
            }
            .bind()
        encodeResult(json, preset).bind()
    }
}
