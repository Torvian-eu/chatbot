package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.core.LLMModelService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `list_models` server built-in tool.
 *
 * Returns every [eu.torvian.chatbot.common.models.llm.LLMModel] accessible by the current user
 * (owned or group-shared, `AccessMode.READ`). The tool accepts no input parameters; any supplied
 * argument is rejected as invalid input.
 *
 * @property llmModelService User-scoped model service used to load the caller's accessible models.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListModelsTool(
    private val llmModelService: LLMModelService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_MODELS_NAME

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
        // Parameterless tool: reject any argument so hallucinated parameters surface to the LLM.
        addUnknownParameterErrors(input, emptySet(), validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        val models = llmModelService.getAllAccessibleModels(userId, AccessMode.READ)
        encodeResult(json, models).bind()
    }
}
