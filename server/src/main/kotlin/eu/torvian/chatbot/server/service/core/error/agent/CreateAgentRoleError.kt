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
     * The referenced model preset does not exist or is not owned by the requesting user.
     *
     * The same error shape covers a missing preset and a foreign one, so the request cannot tell an
     * ownership mismatch apart from a plain non-existent id (no existence leak).
     *
     * @property presetId The missing or foreign model-preset identifier.
     */
    data class ModelPresetNotFound(val presetId: Long) : CreateAgentRoleError

    /**
     * The attached model preset's settings profile is not chat-capable (not CHAT or RESPONSES).
     *
     * Raised only when the preset carries a non-null settings reference that cannot drive a chat turn.
     * A preset whose settings reference is null is attachable (that is the state `ON DELETE SET NULL`
     * produces when a settings profile is deleted) and yields a non-sendable role instead.
     *
     * @property presetId The offending model-preset identifier.
     * @property settingsId The referenced settings identifier.
     * @property actualType The settings subtype name.
     */
    data class ModelPresetNotChatLike(
        val presetId: Long,
        val settingsId: Long,
        val actualType: String
    ) : CreateAgentRoleError

    /**
     * The attached model preset's settings profile belongs to a different model than the preset.
     *
     * Raised only when both the preset's model and settings references are non-null and disagree —
     * reachable when the settings profile was re-pointed to another model after the preset was
     * written.
     *
     * @property presetId The offending model-preset identifier.
     * @property presetModelId The model the preset references.
     * @property settingsModelId The model the settings profile actually belongs to.
     */
    data class ModelPresetSettingsModelMismatch(
        val presetId: Long,
        val presetModelId: Long,
        val settingsModelId: Long
    ) : CreateAgentRoleError

    /**
     * One of the referenced tool definitions does not exist or is not accessible.
     *
     * @property toolId The missing tool identifier.
     */
    data class ToolNotFound(val toolId: Long) : CreateAgentRoleError

    /**
     * A requested spawn target is missing or owned by another user.
     *
     * @property roleId The inaccessible target role identifier.
     */
    data class SpawnableRoleNotFound(val roleId: Long) : CreateAgentRoleError

    /**
     * A requested spawn target exists but belongs to a different project scope than the role.
     *
     * Spawn targets must be same-project: an in-project role may only spawn roles of the same
     * project, an unassociated role only unassociated roles.
     *
     * @property roleId The offending target role identifier.
     * @property projectId The scope the source role occupies (null = unassociated); the target does
     *            not occupy this scope.
     */
    data class SpawnableRoleNotInProject(val roleId: Long, val projectId: Long?) : CreateAgentRoleError

    /**
     * One of the referenced projects does not exist or is not owned by the requesting user.
     *
     * @property projectId The missing or foreign project identifier.
     */
    data class ProjectNotFound(val projectId: Long) : CreateAgentRoleError

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

    is CreateAgentRoleError.ModelPresetNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model preset not found", "presetId" to presetId.toString())

    is CreateAgentRoleError.ModelPresetNotChatLike ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Model preset settings profile must be CHAT or RESPONSES",
            "presetId" to presetId.toString(),
            "settingsId" to settingsId.toString(),
            "actualType" to actualType
        )

    is CreateAgentRoleError.ModelPresetSettingsModelMismatch ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Model preset settings profile belongs to a different model",
            "presetId" to presetId.toString(),
            "presetModelId" to presetModelId.toString(),
            "settingsModelId" to settingsModelId.toString()
        )

    is CreateAgentRoleError.ToolNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Tool definition not found", "toolId" to toolId.toString())

    is CreateAgentRoleError.SpawnableRoleNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Spawnable agent role not found", "roleId" to roleId.toString())

    is CreateAgentRoleError.SpawnableRoleNotInProject ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Spawnable agent role does not belong to the role's project",
            "roleId" to roleId.toString(),
            "projectId" to (projectId?.toString() ?: "null")
        )

    is CreateAgentRoleError.ProjectNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Project not found", "projectId" to projectId.toString())

    is CreateAgentRoleError.InstructionValidationFailed ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid agent role instructions: $reason")

    is CreateAgentRoleError.OwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set agent role ownership: $reason")
}
