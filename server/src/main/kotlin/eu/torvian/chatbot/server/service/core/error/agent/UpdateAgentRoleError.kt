package eu.torvian.chatbot.server.service.core.error.agent

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when updating an agent role.
 */
sealed interface UpdateAgentRoleError {

    /**
     * The agent role to update was not found.
     *
     * @property id The missing role identifier.
     */
    data class NotFound(val id: Long) : UpdateAgentRoleError

    /**
     * The provided role name is invalid (blank or too long).
     *
     * @property name The invalid role name.
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : UpdateAgentRoleError

    /**
     * A different role with the specified name already exists for this user.
     *
     * @property name The conflicting role name.
     */
    data class NameAlreadyExists(val name: String) : UpdateAgentRoleError

    /**
     * The referenced model preset does not exist or is not owned by the requesting user.
     *
     * The same error shape covers a missing preset and a foreign one, so the request cannot tell an
     * ownership mismatch apart from a plain non-existent id (no existence leak).
     *
     * @property presetId The missing or foreign model-preset identifier.
     */
    data class ModelPresetNotFound(val presetId: Long) : UpdateAgentRoleError

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
    ) : UpdateAgentRoleError

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
    ) : UpdateAgentRoleError

    /**
     * One of the referenced tool definitions does not exist or is not accessible.
     *
     * @property toolId The missing tool identifier.
     */
    data class ToolNotFound(val toolId: Long) : UpdateAgentRoleError

    /**
     * A requested spawn target is missing or owned by another user.
     *
     * @property roleId The inaccessible target role identifier.
     */
    data class SpawnableRoleNotFound(val roleId: Long) : UpdateAgentRoleError

    /**
     * One of the referenced projects does not exist or is not owned by the requesting user.
     *
     * @property projectId The missing or foreign project identifier.
     */
    data class ProjectNotFound(val projectId: Long) : UpdateAgentRoleError

    /**
     * The instruction list violates the agent-role instruction rules (e.g. duplicate singleton kinds).
     *
     * @property reason Human-readable explanation of the validation failure.
     */
    data class InstructionValidationFailed(val reason: String) : UpdateAgentRoleError

    /**
     * A referenced instruction row does not exist or is not owned by the requesting user.
     *
     * Missing and foreign ids collapse to the same error, so the request cannot tell an ownership
     * mismatch apart from a plain non-existent id (no existence leak).
     *
     * @property instructionId The missing or foreign instruction identifier.
     */
    data class InstructionNotFound(val instructionId: Long) : UpdateAgentRoleError

    /**
     * The request links the same instruction row to the role more than once.
     *
     * @property instructionId The duplicated instruction identifier.
     */
    data class DuplicateInstructionLink(val instructionId: Long) : UpdateAgentRoleError

    /**
     * The ownership link for an instruction row created inline by this write could not be inserted.
     *
     * A created row without an owner would be unmodifiable, so the whole save fails instead of
     * leaving it behind.
     *
     * @property reason Human-readable explanation of the failure.
     */
    data class InstructionOwnerInsertFailed(val reason: String) : UpdateAgentRoleError

    /**
     * An inline spec would change the kind or model target of a row another agent role also links,
     * leaving that role's instruction list invalid.
     *
     * The row is shared content, so its kind is part of every linking role's list validity; this save
     * only judges the list of the role being written, so the change is allowed only when every other
     * linking role's resulting list still satisfies the per-role rules.
     *
     * @property instructionId The row whose content was to change.
     * @property linkedRoleIds The other roles whose resulting lists would be invalid, ascending.
     * @property reason Human-readable description of the violated rule.
     */
    data class LinkedRoleInstructionListInvalid(
        val instructionId: Long,
        val linkedRoleIds: List<Long>,
        val reason: String
    ) : UpdateAgentRoleError
}

/**
 * Converts an [UpdateAgentRoleError] to its [ApiError] representation.
 */
fun UpdateAgentRoleError.toApiError(): ApiError = when (this) {
    is UpdateAgentRoleError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Agent role not found", "roleId" to id.toString())

    is UpdateAgentRoleError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid agent role name: $reason", "name" to name)

    is UpdateAgentRoleError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Agent role name already exists", "name" to name)

    is UpdateAgentRoleError.ModelPresetNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model preset not found", "presetId" to presetId.toString())

    is UpdateAgentRoleError.ModelPresetNotChatLike ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Model preset settings profile must be CHAT or RESPONSES",
            "presetId" to presetId.toString(),
            "settingsId" to settingsId.toString(),
            "actualType" to actualType
        )

    is UpdateAgentRoleError.ModelPresetSettingsModelMismatch ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Model preset settings profile belongs to a different model",
            "presetId" to presetId.toString(),
            "presetModelId" to presetModelId.toString(),
            "settingsModelId" to settingsModelId.toString()
        )

    is UpdateAgentRoleError.ToolNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Tool definition not found", "toolId" to toolId.toString())

    is UpdateAgentRoleError.SpawnableRoleNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Spawnable agent role not found", "roleId" to roleId.toString())

    is UpdateAgentRoleError.ProjectNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Project not found", "projectId" to projectId.toString())

    is UpdateAgentRoleError.InstructionValidationFailed ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid agent role instructions: $reason")

    is UpdateAgentRoleError.InstructionNotFound ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Instruction not found",
            "instructionId" to instructionId.toString()
        )

    is UpdateAgentRoleError.DuplicateInstructionLink ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "The same instruction cannot be linked to one role twice",
            "instructionId" to instructionId.toString()
        )

    is UpdateAgentRoleError.InstructionOwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set instruction ownership: $reason")

    is UpdateAgentRoleError.LinkedRoleInstructionListInvalid ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Instruction change would leave agent role(s) ${linkedRoleIds.joinToString()} with an " +
                "invalid instruction list: $reason",
            "instructionId" to instructionId.toString(),
            "roleIds" to linkedRoleIds.joinToString()
        )
}
