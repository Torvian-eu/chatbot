package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
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
 * Creates a role from the parsed input, reusing [CreateAgentRoleRequest]. `model_id` and
 * `model_settings_id` are optional: a role may be created without a model/settings and completed
 * later via `update_agent_role`; such a role is non-sendable until set.
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
        userId: Long,
        input: JsonObject
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(
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
        val name = parseRequiredString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
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

        val request = CreateAgentRoleRequest(
            name = name!!,
            displayName = displayName,
            description = description ?: "",
            modelId = modelId,
            modelSettingsId = modelSettingsId,
            toolIds = toolIds ?: emptySet(),
            spawnableAgentRoleIds = spawnableAgentRoleIds ?: emptySet(),
            instructions = instructions ?: emptyList()
        )
        val role = agentRoleService.createRole(userId, request)
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
    is CreateAgentRoleError.ModelNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_not_found",
            "Model $modelId not found or not accessible by the current user."
        )
    is CreateAgentRoleError.SettingsNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_not_found",
            "Settings profile $settingsId not found or not accessible by the current user."
        )
    is CreateAgentRoleError.SettingsNotChatLike ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_not_chat_like",
            "Settings profile $settingsId is of type $actualType; only CHAT or RESPONSES " +
                "settings are supported."
        )
    is CreateAgentRoleError.SettingsModelMismatch ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "settings_model_mismatch",
            "Settings profile $settingsId belongs to model $settingsModelId, not $roleModelId."
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
    is CreateAgentRoleError.InstructionValidationFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("instruction_validation_failed", reason)
    is CreateAgentRoleError.OwnerInsertFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("owner_insert_failed", reason)
}
