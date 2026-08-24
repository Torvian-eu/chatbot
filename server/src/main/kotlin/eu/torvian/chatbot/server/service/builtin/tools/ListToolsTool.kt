package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolSummary
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.core.ToolService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `list_tools` server built-in tool.
 *
 * Returns slim [ToolSummary]s (`id`, `name`, `description`, `type`, `isEnabled`) of every tool
 * accessible by the current user — MCP tools, worker built-in tools of owned workers, own operator
 * tools, and own server built-in tools — without exposing config or schema payloads. The tool
 * accepts no input parameters; any supplied argument is rejected as invalid input.
 *
 * @property toolService User-scoped tool service used to load the caller's tool set.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListToolsTool(
    private val toolService: ToolService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_TOOLS_NAME

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

        val tools = toolService.getToolsForUser(userId)
        val summaries = tools.map { tool ->
            ToolSummary(
                id = tool.id,
                name = tool.name,
                description = tool.description,
                type = tool.type,
                isEnabled = tool.isEnabled
            )
        }
        encodeResult(json, summaries).bind()
    }
}
