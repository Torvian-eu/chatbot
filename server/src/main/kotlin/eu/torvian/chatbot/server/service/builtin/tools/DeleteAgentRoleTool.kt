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
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import kotlinx.serialization.json.JsonObject

/**
 * `delete_agent_role` server built-in tool.
 *
 * Deletes the ownership-checked agent role with the given id. The role service's delete
 * ([AgentRoleService.deleteRole]) enforces ownership in the same transaction and collapses foreign
 * and nonexistent roles into a single not-found error; this tool maps that error to
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] so it never leaks the existence of
 * another user's role (id-enumeration guard).
 *
 * Deleting is non-destructive for sessions: `chat_sessions.agent_role_id` and
 * `assistant_messages.agent_role_id` are set null, so affected sessions keep their history and
 * become inert until another role is re-selected. Returns a concise one-line summary of the
 * completed operation (see [formatDeletedAgentRole]) instead of the full role JSON.
 *
 * @property agentRoleService User-scoped role service used to delete the role.
 */
class DeleteAgentRoleTool(
    private val agentRoleService: AgentRoleService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.DELETE_AGENT_ROLE_NAME

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
            setOf(ServerBuiltInToolCatalog.ROLE_ID_PROPERTY),
            validationErrors
        )
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }
        // roleId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        agentRoleService.deleteRole(context.userId, roleId!!)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatDeletedAgentRole(roleId)
    }
}

/**
 * Maps a [DeleteAgentRoleError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * The only delete failure is not-found, which the service already collapsed for both foreign and
 * nonexistent roles, so this mapping surfaces a single not-found message that never discloses who
 * owns a role.
 *
 * @receiver The typed delete-role failure.
 * @return The corresponding handler error.
 */
private fun DeleteAgentRoleError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is DeleteAgentRoleError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Agent role $id not found or not accessible by the current user."
        )
}