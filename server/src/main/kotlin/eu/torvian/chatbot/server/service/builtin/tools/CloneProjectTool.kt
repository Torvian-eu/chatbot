package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseOptionalString
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.builtin.parseRequiredString
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.CloneProjectError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `clone_project` server built-in tool.
 *
 * Clones a user-owned project under a caller-provided name, reusing [CloneProjectRequest] and
 * [ProjectService.cloneProject]. `project_id` and `name` are required; `description` is optional —
 * omitted/null copies the source project's description, a provided value overrides it. The source
 * project's member agent roles are deep-copied as new role rows bound to the clone; the source is
 * left untouched. The tool is strictly user-scoped: a foreign or nonexistent source collapses to a
 * single [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] (no existence leak).
 *
 * Returns the cloned project's full [ProjectDto] JSON (including the server-generated id, creation
 * time, and the new member role ids), consistent with `create_project` and the read-side project
 * tools.
 *
 * @property projectService User-scoped project service used to clone the project.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class CloneProjectTool(
    private val projectService: ProjectService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.CLONE_PROJECT_NAME

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
                ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY
            ),
            validationErrors
        )
        val projectId = parseRequiredLong(input, ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY, validationErrors)
        val name = parseRequiredString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val description = parseOptionalString(input, ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // projectId/name are non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded. The optional description stays
        // null (omitted) so the service defaults it to the source's description.
        val request = CloneProjectRequest(name = name!!, description = description)
        val project = projectService.cloneProject(context.userId, projectId!!, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        encodeResult(json, project).bind()
    }
}

/**
 * Maps a [CloneProjectError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * Keeps the no-existence-leak convention: a foreign and a nonexistent source collapse into the same
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible], exactly like the service does.
 *
 * @receiver The typed clone failure.
 * @return The corresponding handler error.
 */
private fun CloneProjectError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is CloneProjectError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Project $id not found or not owned by the current user."
        )
    is CloneProjectError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid project name: $reason")
    is CloneProjectError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A project named '$name' already exists for the current user."
        )
    is CloneProjectError.OwnerInsertFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("owner_insert_failed", reason)
}