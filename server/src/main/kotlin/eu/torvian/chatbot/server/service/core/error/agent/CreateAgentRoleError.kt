package eu.torvian.chatbot.server.service.core.error.agent

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when creating an agent role.
 */
sealed interface CreateAgentRoleError {

    /**
     * The provided role name is invalid (blank or too long).
     *
     * @property name The invalid role name.
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : CreateAgentRoleError

    /**
     * A role with the specified name already exists for this user.
     *
     * @property name The conflicting role name.
     */
    data class NameAlreadyExists(val name: String) : CreateAgentRoleError

    /**
     * The referenced model does not exist or is not accessible.
     *
     * @property modelId The missing model identifier.
     */
    data class ModelNotFound(val modelId: Long) : CreateAgentRoleError

    /**
     * The referenced settings profile does not exist.
     *
     * @property settingsId The missing settings identifier.
     */
    data class SettingsNotFound(val settingsId: Long) : CreateAgentRoleError

    /**
     * The referenced settings profile is not chat-capable (not CHAT or RESPONSES).
     *
     * @property settingsId The settings identifier.
     * @property actualType The settings subtype name.
     */
    data class SettingsNotChatLike(val settingsId: Long, val actualType: String) : CreateAgentRoleError

    /**
     * The referenced settings profile belongs to a different model than the role's model.
     *
     * @property settingsId The settings identifier.
     * @property settingsModelId The model the settings belong to.
     * @property roleModelId The model the role references.
     */
    data class SettingsModelMismatch(
        val settingsId: Long,
        val settingsModelId: Long,
        val roleModelId: Long
    ) : CreateAgentRoleError

    /**
     * One of the referenced tool definitions does not exist or is not accessible.
     *
     * @property toolId The missing tool identifier.
     */
    data class ToolNotFound(val toolId: Long) : CreateAgentRoleError

    /**
     * The instruction list violates the agent-role instruction rules (e.g. duplicate singleton kinds).
     *
     * @property reason Human-readable explanation of the validation failure.
     */
    data class InstructionValidationFailed(val reason: String) : CreateAgentRoleError

    /**
     * The ownership link for the newly created role could not be inserted.
     *
     * @property reason Human-readable explanation of the failure.
     */
    data class OwnerInsertFailed(val reason: String) : CreateAgentRoleError
}

/**
 * Converts a [CreateAgentRoleError] to its [ApiError] representation.
 */
fun CreateAgentRoleError.toApiError(): ApiError = when (this) {
    is CreateAgentRoleError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid agent role name: $reason", "name" to name)

    is CreateAgentRoleError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Agent role name already exists", "name" to name)

    is CreateAgentRoleError.ModelNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model not found", "modelId" to modelId.toString())

    is CreateAgentRoleError.SettingsNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Settings profile not found", "settingsId" to settingsId.toString())

    is CreateAgentRoleError.SettingsNotChatLike ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Settings profile must be CHAT or RESPONSES",
            "settingsId" to settingsId.toString(),
            "actualType" to actualType
        )

    is CreateAgentRoleError.SettingsModelMismatch ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Settings profile belongs to a different model",
            "settingsId" to settingsId.toString(),
            "settingsModelId" to settingsModelId.toString(),
            "roleModelId" to roleModelId.toString()
        )

    is CreateAgentRoleError.ToolNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Tool definition not found", "toolId" to toolId.toString())

    is CreateAgentRoleError.InstructionValidationFailed ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid agent role instructions: $reason")

    is CreateAgentRoleError.OwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set agent role ownership: $reason")
}
