package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.ToolService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `read_tool` server built-in tool.
 *
 * Returns the full polymorphic [eu.torvian.chatbot.common.models.tool.ToolDefinition] for one id.
 * The id must be present in the user's tool set — a single ownership rule covering MCP, worker
 * built-in, operator, and server built-in tools. Not-found and not-accessible collapse into one
 * message.
 *
 * @property toolService User-scoped tool service used for the ownership-checked lookup.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ReadToolTool(
    private val toolService: ToolService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.READ_TOOL_NAME

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
        addUnknownParameterErrors(input, setOf(ServerBuiltInToolCatalog.TOOL_ID_PROPERTY), validationErrors)
        val toolId = parseRequiredLong(input, ServerBuiltInToolCatalog.TOOL_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // toolId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        val userTools = toolService.getToolsForUser(userId)
        val tool = userTools.firstOrNull { it.id == toolId }
            ?: raise(
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Tool $toolId not found or not accessible by the current user."
                )
            )
        encodeResult(json, tool).bind()
    }
}
