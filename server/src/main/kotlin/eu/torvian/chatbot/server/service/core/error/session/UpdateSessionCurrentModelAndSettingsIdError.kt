package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.ApiError

/**
 * Represents possible errors when updating a chat session's current model ID and settings ID atomically.
 * This covers all error scenarios that can occur during the combined operation.
 */
sealed interface UpdateSessionCurrentModelAndSettingsIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided model ID was not found or is invalid.
     */
    data class ModelNotFound(val modelId: Long) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided settings ID was not found.
     */
    data class SettingsNotFound(val settingsId: Long) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided settings are not compatible with the specified model.
     * This occurs when trying to assign settings that belong to a different model.
     */
    data class SettingsModelMismatch(
        val settingsId: Long,
        val settingsModelId: Long,
        val providedModelId: Long
    ) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates a foreign key constraint violation or other database-related validation error.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the provided settings are not of the ChatModelSettings type.
     * This occurs when trying to assign settings that are not suitable for chat sessions.
     */
    data class InvalidSettingsType(val settingsId: Long, val actualType: String) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the model exists but doesn't have the required CHAT type.
     */
    data class InvalidModelType(val modelId: Long, val actualType: String) : UpdateSessionCurrentModelAndSettingsIdError

    /**
     * Indicates that the model exists but is deprecated (not active).
     */
    data class DeprecatedModel(val modelId: Long) : UpdateSessionCurrentModelAndSettingsIdError
}

fun UpdateSessionCurrentModelAndSettingsIdError.toApiError(): ApiError = when (this) {
    is UpdateSessionCurrentModelAndSettingsIdError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionCurrentModelAndSettingsIdError.ModelNotFound -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid model ID provided",
        "modelId" to modelId.toString()
    )

    is UpdateSessionCurrentModelAndSettingsIdError.SettingsNotFound -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid settings ID provided",
        "settingsId" to settingsId.toString()
    )

    is UpdateSessionCurrentModelAndSettingsIdError.SettingsModelMismatch -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Settings are not compatible with the selected model",
        "settingsId" to settingsId.toString(),
        "settingsModelId" to settingsModelId.toString(),
        "modelId" to providedModelId.toString()
    )

    is UpdateSessionCurrentModelAndSettingsIdError.InvalidRelatedEntity -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid related entity",
        "reason" to message
    )

    is UpdateSessionCurrentModelAndSettingsIdError.InvalidSettingsType -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Settings type must be CHAT",
        "settingsId" to settingsId.toString(),
        "actualType" to actualType
    )

    is UpdateSessionCurrentModelAndSettingsIdError.InvalidModelType -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Model type must be CHAT",
        "modelId" to modelId.toString(),
        "actualType" to actualType
    )

    is UpdateSessionCurrentModelAndSettingsIdError.DeprecatedModel -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Model is deprecated and cannot be used",
        "modelId" to modelId.toString()
    )
}

