package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError

/**
 * Maps an [UpdateAgentRoleError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * Shared by every server built-in tool that mutates an agent role through the role service
 * (`update_agent_role`, `insert_agent_role_instruction`, `edit_agent_role_instructions`, and
 * `remove_agent_role_instruction`), so all of them surface identical error codes and messages for
 * the same underlying failure.
 *
 * @receiver The typed update-role failure.
 * @return The corresponding handler error.
 */
internal fun UpdateAgentRoleError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is UpdateAgentRoleError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Agent role $id not found or not accessible by the current user."
        )
    is UpdateAgentRoleError.InvalidName ->
        ServerBuiltInToolHandlerError.OperationFailed("invalid_name", "Invalid role name: $reason")
    is UpdateAgentRoleError.NameAlreadyExists ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "name_already_exists",
            "A role named '$name' already exists for the current user."
        )
    is UpdateAgentRoleError.ModelPresetNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_preset_not_found",
            "Model preset $presetId not found or not owned by the current user."
        )
    is UpdateAgentRoleError.ModelPresetNotChatLike ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_preset_not_chat_like",
            "Model preset $presetId uses settings profile $settingsId of type $actualType; only CHAT " +
                "or RESPONSES settings are supported."
        )
    is UpdateAgentRoleError.ModelPresetSettingsModelMismatch ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "model_preset_settings_model_mismatch",
            "Model preset $presetId references model $presetModelId but its settings profile belongs " +
                "to model $settingsModelId."
        )
    is UpdateAgentRoleError.ToolNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "tool_not_found",
            "Tool $toolId not found or not accessible by the current user."
        )
    is UpdateAgentRoleError.SpawnableRoleNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "spawnable_role_not_found",
            "Spawnable agent role $roleId not found or not owned by the current user."
        )
    is UpdateAgentRoleError.SpawnableRoleNotInProject ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "spawnable_role_not_in_project",
            "Spawnable agent role $roleId does not belong to the role's project " +
                "(project id: ${projectId ?: "none"}) — spawn targets must share the role's project scope."
        )
    is UpdateAgentRoleError.ProjectNotFound ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "project_not_found",
            "Project $projectId not found or not owned by the current user."
        )
    is UpdateAgentRoleError.InstructionValidationFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("instruction_validation_failed", reason)
}
