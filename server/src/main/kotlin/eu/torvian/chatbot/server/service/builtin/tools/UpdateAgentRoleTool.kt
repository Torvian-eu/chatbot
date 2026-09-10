package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
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
        input: JsonObject,
        context: ToolCallExecutionContext
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(
                ServerBuiltInToolCatalog.ROLE_ID_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY,
                ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY,
                ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY,
                ServerBuiltInToolCatalog.TOOL_IDS_PROPERTY,
                ServerBuiltInToolCatalog.SPAWNABLE_AGENT_ROLE_IDS_PROPERTY,
                ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY,
                ServerBuiltInToolCatalog.INSTRUCTIONS_PROPERTY
            ),
            validationErrors
        )
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        val name = parseOptionalString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val displayName = parseOptionalString(input, ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY, validationErrors)
        val description = parseOptionalString(input, ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY, validationErrors)
        val modelPresetId =
            parseOptionalLong(input, ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY, validationErrors)
        val toolIds = parseOptionalLongSet(input, ServerBuiltInToolCatalog.TOOL_IDS_PROPERTY, validationErrors)
        val spawnableAgentRoleIds =
            parseOptionalLongSet(input, ServerBuiltInToolCatalog.SPAWNABLE_AGENT_ROLE_IDS_PROPERTY, validationErrors)
        val projectId = parseOptionalLong(input, ServerBuiltInToolCatalog.PROJECT_ID_PROPERTY, validationErrors)
        val instructions =
            parseOptionalInstructions(input, ServerBuiltInToolCatalog.INSTRUCTIONS_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // roleId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        val persisted = agentRoleService.getRoleById(context.userId, roleId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role $roleId not found or not accessible by the current user."
                )
            }
            .bind()

        // The project id follows the patch merge for absent/null inputs, with one LLM-facing
        // extension: the sentinel 0 explicitly clears the membership (project ids are always
        // positive AUTOINCREMENT database ids, so 0 can never collide with a real project).
        // Without the sentinel a caller could never unassociate a role through this tool, since
        // an omitted or explicitly-null project_id must preserve the persisted value.
        val targetProjectId = when (projectId) {
            null -> persisted.projectId
            0L -> null
            else -> projectId
        }

        // The model preset follows the same patch merge, including the LLM-facing `0` sentinel that
        // detaches the preset (preset ids are positive AUTOINCREMENT values, so 0 is unambiguous).
        // Without it a caller could never make a role preset-less through this tool, since an omitted
        // or explicitly-null model_preset_id must preserve the persisted value.
        val targetModelPresetId = when (modelPresetId) {
            null -> persisted.modelPresetId
            0L -> null
            else -> modelPresetId
        }

        val request = UpdateAgentRoleRequest(
            name = name ?: persisted.name,
            displayName = displayName ?: persisted.displayName,
            description = description ?: persisted.description,
            modelPresetId = targetModelPresetId,
            toolIds = toolIds ?: persisted.tools,
            spawnableAgentRoleIds = spawnableAgentRoleIds ?: persisted.spawnableAgentRoleIds,
            instructions = instructions ?: persisted.instructions,
            projectId = targetProjectId
        )

        val role = agentRoleService.updateRole(context.userId, roleId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatUpdatedAgentRole(role)
    }
}
