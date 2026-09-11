package eu.torvian.chatbot.server.service.core.error.preset

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when updating a model preset.
 */
sealed interface UpdateModelPresetError {

    /**
     * The model preset to update was not found or is not owned by the requesting user.
     *
     * @property id The missing or foreign preset identifier.
     */
    data class NotFound(val id: Long) : UpdateModelPresetError

    /**
     * The provided preset name is invalid (blank or too long).
     *
     * @property name The invalid preset name (already trimmed).
     * @property reason Human-readable explanation of why the name is invalid.
     */
    data class InvalidName(val name: String, val reason: String) : UpdateModelPresetError

    /**
     * A different model preset with the specified name already exists for this owner.
     *
     * @property name The conflicting preset name.
     */
    data class NameAlreadyExists(val name: String) : UpdateModelPresetError

    /**
     * The referenced model does not exist or is not readable by the requesting user.
     *
     * @property modelId The missing or inaccessible model identifier.
     */
    data class ModelNotFound(val modelId: Long) : UpdateModelPresetError

    /**
     * The referenced settings profile does not exist or is not readable by the requesting user.
     *
     * @property settingsId The missing or inaccessible settings identifier.
     */
    data class SettingsNotFound(val settingsId: Long) : UpdateModelPresetError

    /**
     * The referenced settings profile belongs to a different model than the preset's model.
     *
     * @property settingsId The settings identifier.
     * @property settingsModelId The model the settings profile belongs to.
     * @property presetModelId The model the preset references.
     */
    data class SettingsModelMismatch(
        val settingsId: Long,
        val settingsModelId: Long,
        val presetModelId: Long
    ) : UpdateModelPresetError
}

/**
 * Converts an [UpdateModelPresetError] to its [ApiError] representation.
 *
 * A foreign preset is reported identically to a nonexistent one, so ownership never leaks through the
 * error surface.
 */
fun UpdateModelPresetError.toApiError(): ApiError = when (this) {
    is UpdateModelPresetError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Model preset not found", "presetId" to id.toString())

    is UpdateModelPresetError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model preset name: $reason", "name" to name)

    is UpdateModelPresetError.NameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Model preset name already exists", "name" to name)

    is UpdateModelPresetError.ModelNotFound ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model not found", "modelId" to modelId.toString())

    is UpdateModelPresetError.SettingsNotFound ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Settings profile not found",
            "settingsId" to settingsId.toString()
        )

    is UpdateModelPresetError.SettingsModelMismatch ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Settings profile belongs to a different model",
            "settingsId" to settingsId.toString(),
            "settingsModelId" to settingsModelId.toString(),
            "presetModelId" to presetModelId.toString()
        )
}
