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
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `read_agent_role` server built-in tool.
 *
 * Returns the ownership-checked [eu.torvian.chatbot.common.models.agent.AgentRoleDto] (wire-safe,
 * resolved instructions) for one role id. Not-found and not-accessible collapse into a single
 * message to avoid id enumeration.
 *
 * @property agentRoleService User-scoped role service used for the ownership-checked lookup.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ReadAgentRoleTool(
    private val agentRoleService: AgentRoleService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.READ_AGENT_ROLE_NAME

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
        addUnknownParameterErrors(input, setOf(ServerBuiltInToolCatalog.ROLE_ID_PROPERTY), validationErrors)
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }
        // roleId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        val role = agentRoleService.getRoleById(userId, roleId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role $roleId not found or not accessible by the current user."
                )
            }
            .bind()
        encodeResult(json, role).bind()
    }
}
