package eu.torvian.chatbot.server.service.core.error.preset

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when creating a model preset.
 */
sealed interface CreateModelPresetError {

    /**
     * The provided preset name is invalid (blank or too long).
     *
     * @property name The invalid preset name (already trimmed).
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : CreateModelPresetError

    /**
     * A model preset with the specified name already exists for this owner.
     *
     * @property name The conflicting preset name.
     */
    data class NameAlreadyExists(val name: String) : CreateModelPresetError

    /**
     * The referenced model does not exist or is not readable by the requesting user.
     *
     * The same error shape covers a missing model and a model the user cannot `READ`, so the request
     * cannot tell an access mismatch apart from a plain non-existent id (no existence leak).
     *
     * @property modelId The missing or inaccessible model identifier.
     */
    data class ModelNotFound(val modelId: Long) : CreateModelPresetError

    /**
     * The referenced settings profile does not exist or is not readable by the requesting user.
     *
     * The same error shape covers a missing profile and one the user cannot `READ` (no existence
     * leak), mirroring [ModelNotFound].
     *
     * @property settingsId The missing or inaccessible settings identifier.
     */
    data class SettingsNotFound(val settingsId: Long) : CreateModelPresetError

    /**
     * The referenced settings profile belongs to a different model than the preset's model.
     *
     * A preset bundles one model and one settings profile describing it, so the two references must
     * agree. The check runs only when both references are present.
     *
     * @property settingsId The settings identifier.
     * @property settingsModelId The model the settings profile belongs to.
     * @property presetModelId The model the preset references.
     */
    data class SettingsModelMismatch(
        val settingsId: Long,
        val settingsModelId: Long,
        val presetModelId: Long
    ) : CreateModelPresetError

    /**
     * The ownership link for the newly created preset could not be inserted.
     *
     * @property reason Human-readable explanation of the failure.
     */
    data class OwnerInsertFailed(val reason: String) : CreateModelPresetError
}

/**
 * Converts a [CreateModelPresetError] to its [ApiError] representation.
 */
fun CreateModelPresetError.toApiError(): ApiError = when (this) {
    is CreateModelPresetError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model preset name: $reason", "name" to name)

    is CreateModelPresetError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Model preset name already exists", "name" to name)

    is CreateModelPresetError.ModelNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model not found", "modelId" to modelId.toString())

    is CreateModelPresetError.SettingsNotFound ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Settings profile not found",
            "settingsId" to settingsId.toString()
        )

    is CreateModelPresetError.SettingsModelMismatch ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Settings profile belongs to a different model",
            "settingsId" to settingsId.toString(),
            "settingsModelId" to settingsModelId.toString(),
            "presetModelId" to presetModelId.toString()
        )

    is CreateModelPresetError.OwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set model preset ownership: $reason")
}
