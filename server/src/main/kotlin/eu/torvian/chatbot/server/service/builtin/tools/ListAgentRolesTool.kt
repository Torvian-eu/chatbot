package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeJsonElement
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list_agent_roles` server built-in tool.
 *
 * Returns all ten [eu.torvian.chatbot.common.models.agent.AgentRoleDto] properties for every agent
 * role owned by the current user, with instructions summarized to their type strings. The tool
 * accepts no input parameters; any supplied argument is rejected as invalid input.
 *
 * @property agentRoleService User-scoped role service used to load the caller's roles.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListAgentRolesTool(
    private val agentRoleService: AgentRoleService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME

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

        val roles = agentRoleService.getAllRolesForUser(userId)
        val summaries = buildJsonArray {
            roles.forEach { role ->
                add(
                    buildJsonObject {
                        put("id", role.id)
                        put("name", role.name)
                        // Keep nullable DTO properties explicit so consumers can distinguish null from omission.
                        put("displayName", role.displayName?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("description", role.description)
                        put("modelId", role.modelId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("modelSettingsId", role.modelSettingsId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("tools", buildJsonArray {
                            role.tools.forEach { add(it) }
                        })
                        put("spawnableAgentRoleIds", buildJsonArray {
                            role.spawnableAgentRoleIds.forEach { add(it) }
                        })
                        // Per-user disabled flag: boolean, never omitted, mirrors the explicit-null
                        // handling style used for nullable fields above.
                        put("disabled", role.disabled)
                        // Only instruction types are exposed; names, messages, and custom metadata belong to read_agent_role.
                        put("instructions", buildJsonArray {
                            role.instructions.forEach { add(it.type) }
                        })
                    }
                )
            }
        }
        encodeJsonElement(json, summaries).bind()
    }
}
