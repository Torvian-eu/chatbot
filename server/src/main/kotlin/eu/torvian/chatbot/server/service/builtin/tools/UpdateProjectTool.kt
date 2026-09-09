package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.*
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.UpdateProjectError
import kotlinx.serialization.json.JsonObject

/**
 * `update_project` server built-in tool.
 *
 * Implements PATCH semantics (mirroring `update_agent_role`): `project_id` plus only the provided
 * fields. The persisted project is loaded via the ownership-checked lookup and each provided field
 * is merged over it; omitted or explicitly-null fields (`name`, `description`, `agent_role_ids`)
 * are preserved, so a partial payload never wipes the project's state. An explicit empty string
 * for `description` or an empty array for `agent_role_ids` clears the corresponding field (`name`
 * cannot be cleared — a blank name is rejected by the project service). The merged state is then
 * applied through the existing full-replacement project update, keeping the service's validation
 * and membership/session sweeps intact.
 *
 * Returns a concise one-line summary of the completed operation (see [formatUpdatedProject])
 * instead of the full project JSON to keep the LLM context lean; `read_project` returns the full project.
 *
 * @property projectService User-scoped project service used for the ownership-checked load and update.
 */
class UpdateProjectTool(
    private val projectService: ProjectService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.UPDATE_PROJECT_NAME

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
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.AGENT_ROLE_IDS_PROPERTY
            ),
            validationErrors
        )
        val projectId = parseRequiredLong(input, ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY, validationErrors)
        val name = parseOptionalString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val description =
            parseOptionalString(input, ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY, validationErrors)
        val agentRoleIds =
            parseOptionalLongSet(input, ServerBuiltInToolCatalog.AGENT_ROLE_IDS_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // projectId is non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded.
        val persisted = projectService.getProjectById(context.userId, projectId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Project $projectId not found or not accessible by the current user."
                )
            }
            .bind()

        // PATCH merge: an omitted or explicitly-null field preserves the persisted value, while a
        // present value (including an explicit empty string for description or an empty array for
        // agent_role_ids) replaces it — parseOptionalString/parseOptionalLongSet decode both
        // absent and null to null and pass present values through untouched, which is exactly the
        // merge contract.
        val request = UpdateProjectRequest(
            name = name ?: persisted.name,
            description = description ?: persisted.description,
            agentRoleIds = agentRoleIds ?: persisted.agentRoleIds
        )

        val updated = projectService.updateProject(context.userId, projectId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatUpdatedProject(updated)
    }
}

/**
 * Maps an [UpdateProjectError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * The not-found variant surfaces as [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible]
 * (collapsing foreign and nonexistent projects, no existence leak) while the remaining variants
 * map to readable [ServerBuiltInToolHandlerError.OperationFailed] codes.
 *
 * @receiver The typed update-project failure.
 * @return The corresponding handler error.
 */
private fun UpdateProjectError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is UpdateProjectError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Project $id not found or not accessible by the current user."
        )

    is UpdateProjectError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid project name: $reason")

    is UpdateProjectError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A project named '$name' already exists for the current user."
        )

    is UpdateProjectError.RoleNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "role_not_found",
            "Agent role $roleId not found or not owned by the current user."
        )

    is UpdateProjectError.RoleInAnotherProject ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "role_in_another_project",
            "Agent role $roleId already belongs to another project."
        )
}