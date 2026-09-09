package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseOptionalLongSet
import eu.torvian.chatbot.server.service.builtin.parseOptionalString
import eu.torvian.chatbot.server.service.builtin.parseRequiredString
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.CreateProjectError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `create_project` server built-in tool.
 *
 * Creates a user-owned project from the parsed input, reusing [CreateProjectRequest]. `name` is
 * required; `description` and `agent_role_ids` are optional and default to an empty string and an
 * empty set respectively (matching the request DTO defaults), so a fresh project starts with no
 * roles unless the caller attaches some.
 *
 * Returns the created project's full [ProjectDto] JSON (including the server-generated id,
 * creation time, and the attached member role ids) instead of a one-line summary, as explicitly
 * requested for this tool.
 *
 * @property projectService User-scoped project service used to create the project.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class CreateProjectTool(
    private val projectService: ProjectService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.CREATE_PROJECT_NAME

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
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.AGENT_ROLE_IDS_PROPERTY
            ),
            validationErrors
        )
        val name = parseRequiredString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val description =
            parseOptionalString(input, ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY, validationErrors)
        val agentRoleIds =
            parseOptionalLongSet(input, ServerBuiltInToolCatalog.AGENT_ROLE_IDS_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // name is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded. Optional fields fall back to the
        // CreateProjectRequest defaults (empty description, empty membership).
        val request = CreateProjectRequest(
            name = name!!,
            description = description ?: "",
            agentRoleIds = agentRoleIds ?: emptySet()
        )
        val project = projectService.createProject(context.userId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        encodeResult(json, project).bind()
    }
}

/**
 * Maps a [CreateProjectError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * Every projected error keeps the no-existence-leak convention: role ids that are missing or
 * foreign collapse into the same message, exactly like the service does.
 *
 * @receiver The typed create-project failure.
 * @return The corresponding handler error.
 */
private fun CreateProjectError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is CreateProjectError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid project name: $reason")
    is CreateProjectError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A project named '$name' already exists for the current user."
        )
    is CreateProjectError.RoleNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "role_not_found",
            "Agent role $roleId not found or not owned by the current user."
        )
    is CreateProjectError.RoleInAnotherProject ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "role_in_another_project",
            "Agent role $roleId already belongs to another project."
        )
    is CreateProjectError.OwnerInsertFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("owner_insert_failed", reason)
}