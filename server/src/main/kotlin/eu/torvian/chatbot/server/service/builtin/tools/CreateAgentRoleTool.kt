package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
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
import eu.torvian.chatbot.server.service.builtin.parseRequiredString
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import kotlinx.serialization.json.JsonObject

/**
 * `create_agent_role` server built-in tool.
 *
 * Creates a role from the parsed input, reusing [CreateAgentRoleRequest]. `model_preset_id` is
 * optional: a role may be created without a model preset and completed later via `update_agent_role`;
 * such a role is non-sendable until set (the preset supplies both the model and the settings profile).
 *
 * Returns a concise one-line summary of the completed operation (see [formatCreatedAgentRole])
 * instead of the full role JSON to keep the LLM context lean; `read_agent_role` returns the full role.
 *
 * @property agentRoleService User-scoped role service used to create the role.
 */
class CreateAgentRoleTool(
    private val agentRoleService: AgentRoleService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.CREATE_AGENT_ROLE_NAME

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
        val name = parseRequiredString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
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

        val request = CreateAgentRoleRequest(
            name = name!!,
            displayName = displayName,
            description = description ?: "",
            modelPresetId = modelPresetId,
            toolIds = toolIds ?: emptySet(),
            spawnableAgentRoleIds = spawnableAgentRoleIds ?: emptySet(),
            projectId = projectId,
            instructions = instructions ?: emptyList()
        )
        val role = agentRoleService.createRole(context.userId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatCreatedAgentRole(role)
    }
}

/**
 * Maps a [CreateAgentRoleError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * @receiver The typed create-role failure.
 * @return The corresponding handler error.
 */
private fun CreateAgentRoleError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is CreateAgentRoleError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid role name: $reason")
    is CreateAgentRoleError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A role named '$name' already exists for the current user."
        )
    is CreateAgentRoleError.ModelPresetNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_preset_not_found",
            "Model preset $presetId not found or not owned by the current user."
        )
    is CreateAgentRoleError.ModelPresetNotChatLike ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_preset_not_chat_like",
            "Model preset $presetId uses settings profile $settingsId of type $actualType; only CHAT " +
                "or RESPONSES settings are supported."
        )
    is CreateAgentRoleError.ModelPresetSettingsModelMismatch ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_preset_settings_model_mismatch",
            "Model preset $presetId references model $presetModelId but its settings profile belongs " +
                "to model $settingsModelId."
        )
    is CreateAgentRoleError.ToolNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "tool_not_found",
            "Tool $toolId not found or not accessible by the current user."
        )
    is CreateAgentRoleError.SpawnableRoleNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "spawnable_role_not_found",
            "Spawnable agent role $roleId not found or not owned by the current user."
        )
    is CreateAgentRoleError.SpawnableRoleNotInProject ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "spawnable_role_not_in_project",
            "Spawnable agent role $roleId does not belong to the role's project " +
                "(project id: ${projectId ?: "none"}) — spawn targets must share the role's project scope."
        )
    is CreateAgentRoleError.ProjectNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "project_not_found",
            "Project $projectId not found or not owned by the current user."
        )
    is CreateAgentRoleError.InstructionValidationFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("instruction_validation_failed", reason)
    is CreateAgentRoleError.OwnerInsertFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("owner_insert_failed", reason)
}
