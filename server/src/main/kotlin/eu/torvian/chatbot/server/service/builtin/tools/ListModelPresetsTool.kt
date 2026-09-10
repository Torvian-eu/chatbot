package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeJsonElement
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.core.ModelPresetService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `list_model_presets` server built-in tool.
 *
 * Returns every [ModelPresetDto] property for every preset owned by the current user, encoded with
 * the shared JSON codec so the wire shape (including `createdAt`/`updatedAt` and the null
 * references) matches the REST API's `ModelPresetDto` serialization exactly.
 *
 * The order is the service's order — `id` ascending, mandated by the `ModelPresetDaoExposed`
 * query — and it is returned **verbatim**: the tool deliberately does not re-sort, so the ordering
 * contract lives in exactly one place (the DAO/service) and is pinned there. The tool accepts no
 * input parameters; any supplied argument is rejected as invalid input.
 *
 * @property modelPresetService User-scoped model-preset service used to load the caller's presets.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListModelPresetsTool(
    private val modelPresetService: ModelPresetService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_MODEL_PRESETS_NAME

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
        // Parameterless tool: reject any argument so hallucinated parameters surface to the LLM.
        addUnknownParameterErrors(input, emptySet(), validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // Encode the service's list as-is: the id-ascending order is the service/DAO contract and
        // must not be second-guessed here.
        val presets = modelPresetService.getAllPresetsForUser(context.userId)
        encodeJsonElement(json, json.encodeToJsonElement(presets)).bind()
    }
}
