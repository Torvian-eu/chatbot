package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeJsonElement
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.core.ProjectService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `list_projects` server built-in tool.
 *
 * Returns every [ProjectDto] property for every project owned by the current user, encoded with
 * the shared JSON codec so the wire shape (including `createdAt` and `agentRoleIds`) matches the
 * REST API's `ProjectDto` serialization exactly. The tool accepts no input parameters; any
 * supplied argument is rejected as invalid input.
 *
 * @property projectService User-scoped project service used to load the caller's projects.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListProjectsTool(
    private val projectService: ProjectService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_PROJECTS_NAME

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

        val projects = projectService.getAllProjectsForUser(context.userId)
        // Encode the whole DTO list with the shared codec (rather than hand-assembling the array)
        // so createdAt and agentRoleIds match the REST API's ProjectDto wire form exactly.
        encodeJsonElement(json, json.encodeToJsonElement(projects)).bind()
    }
}