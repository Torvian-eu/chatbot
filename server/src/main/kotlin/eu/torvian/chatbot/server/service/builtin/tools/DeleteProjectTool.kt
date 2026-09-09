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
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.DeleteProjectError
import kotlinx.serialization.json.JsonObject

/**
 * `delete_project` server built-in tool.
 *
 * Deletes the ownership-checked project with the given id. Deleting is non-destructive for the
 * project's member agent roles (the service nulls their membership, so they become unassociated)
 * and corrects affected sessions in the same transaction. Not-found and not-accessible collapse
 * into a single message so the tool never leaks the existence of another user's project
 * (id-enumeration guard).
 *
 * Returns a concise one-line summary of the completed operation (see [formatDeletedProject])
 * instead of the full project JSON.
 *
 * @property projectService User-scoped project service used to delete the project.
 */
class DeleteProjectTool(
    private val projectService: ProjectService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.DELETE_PROJECT_NAME

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
            setOf(ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY),
            validationErrors
        )
        val projectId = parseRequiredLong(input, ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }
        // projectId is non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded.
        projectService.deleteProject(context.userId, projectId!!)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatDeletedProject(projectId)
    }
}

/**
 * Maps a [DeleteProjectError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * The only delete failure is not-found/not-accessible, surfaced as
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] (foreign and nonexistent projects
 * collapse, no existence leak).
 *
 * @receiver The typed delete-project failure.
 * @return The corresponding handler error.
 */
private fun DeleteProjectError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is DeleteProjectError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Project $id not found or not accessible by the current user."
        )
}