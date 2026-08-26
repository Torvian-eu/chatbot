package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseOptionalInstructions
import eu.torvian.chatbot.server.service.builtin.parseOptionalLong
import eu.torvian.chatbot.server.service.builtin.parseOptionalLongSet
import eu.torvian.chatbot.server.service.builtin.parseOptionalString
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.JsonObject

/**
 * `update_agent_role` server built-in tool.
 *
 * Implements PATCH semantics: `role_id` plus only the provided fields. The persisted role is loaded
 * via the ownership-checked role lookup and each provided field is merged over it; omitted fields
 * (including `tools`, `spawnable_agent_role_ids`, and `instructions`) are preserved, so a partial
 * payload never wipes the role's configuration. The merged state is then applied through the
 * existing full-replacement role update.
 *
 * Returns a concise one-line summary of the completed operation (see [formatUpdatedAgentRole])
 * instead of the full role JSON to keep the LLM context lean; `read_agent_role` returns the full role.
 *
 * @property agentRoleService User-scoped role service used for the ownership-checked load and update.
 */
class UpdateAgentRoleTool(
    private val agentRoleService: AgentRoleService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.UPDATE_AGENT_ROLE_NAME

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
        addUnknownParameterErrors(
            input,
            setOf(
                ServerBuiltInToolCatalog.ROLE_ID_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_ID_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY,
                ServerBuiltInToolCatalog.TOOL_IDS_PROPERTY,
                ServerBuiltInToolCatalog.SPAWNABLE_AGENT_ROLE_IDS_PROPERTY,
                ServerBuiltInToolCatalog.INSTRUCTIONS_PROPERTY
            ),
            validationErrors
        )
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        val name = parseOptionalString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val displayName = parseOptionalString(input, ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY, validationErrors)
        val description = parseOptionalString(input, ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY, validationErrors)
        val modelId = parseOptionalLong(input, ServerBuiltInToolCatalog.MODEL_ID_PROPERTY, validationErrors)
        val modelSettingsId =
            parseOptionalLong(input, ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY, validationErrors)
        val toolIds = parseOptionalLongSet(input, ServerBuiltInToolCatalog.TOOL_IDS_PROPERTY, validationErrors)
        val spawnableAgentRoleIds =
            parseOptionalLongSet(input, ServerBuiltInToolCatalog.SPAWNABLE_AGENT_ROLE_IDS_PROPERTY, validationErrors)
        val instructions =
            parseOptionalInstructions(input, ServerBuiltInToolCatalog.INSTRUCTIONS_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // roleId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        val persisted = agentRoleService.getRoleById(userId, roleId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role $roleId not found or not accessible by the current user."
                )
            }
            .bind()

        val request = UpdateAgentRoleRequest(
            name = name ?: persisted.name,
            displayName = displayName ?: persisted.displayName,
            description = description ?: persisted.description,
            modelId = modelId ?: persisted.modelId,
            modelSettingsId = modelSettingsId ?: persisted.modelSettingsId,
            toolIds = toolIds ?: persisted.tools,
            spawnableAgentRoleIds = spawnableAgentRoleIds ?: persisted.spawnableAgentRoleIds,
            instructions = instructions ?: persisted.instructions
        )

        val role = agentRoleService.updateRole(userId, roleId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatUpdatedAgentRole(role)
    }
}
