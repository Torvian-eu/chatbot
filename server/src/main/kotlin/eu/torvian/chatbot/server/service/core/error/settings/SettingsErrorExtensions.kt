package eu.torvian.chatbot.server.service.core.error.settings

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.apiError

/**
 * Extension functions to convert settings service errors to API errors.
 */

fun AddSettingsError.toApiError(): ApiError = when (this) {
    is AddSettingsError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid settings input",
        "reason" to this.reason
    )
    is AddSettingsError.ModelNotFound -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Model not found",
        "modelId" to this.modelId.toString()
    )
    is AddSettingsError.OwnershipError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Failed to set settings ownership",
        "reason" to this.reason
    )
}

fun GetSettingsByIdError.toApiError(): ApiError = when (this) {
    is GetSettingsByIdError.SettingsNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Settings not found",
        "settingsId" to this.id.toString()
    )
}

fun UpdateSettingsError.toApiError(): ApiError = when (this) {
    is UpdateSettingsError.SettingsNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Settings not found",
        "settingsId" to this.id.toString()
    )
    is UpdateSettingsError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid settings input",
        "reason" to this.reason
    )
    is UpdateSettingsError.ModelNotFound -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Model not found",
        "modelId" to this.modelId.toString()
    )
}

fun DeleteSettingsError.toApiError(): ApiError = when (this) {
    is DeleteSettingsError.SettingsNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Settings not found",
        "settingsId" to this.id.toString()
    )
}

