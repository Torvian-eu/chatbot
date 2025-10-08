package eu.torvian.chatbot.server.service.core.error.provider

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.apiError

/**
 * Extension functions to convert provider service errors to API errors.
 */

fun AddProviderError.toApiError(): ApiError = when (this) {
    is AddProviderError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid provider input",
        "reason" to this.reason
    )
    is AddProviderError.OwnershipError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Failed to set provider ownership",
        "reason" to this.reason
    )
}

fun GetProviderError.toApiError(): ApiError = when (this) {
    is GetProviderError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Provider not found",
        "providerId" to this.id.toString()
    )
}

fun UpdateProviderError.toApiError(): ApiError = when (this) {
    is UpdateProviderError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Provider not found",
        "providerId" to this.id.toString()
    )
    is UpdateProviderError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid provider input",
        "reason" to this.reason
    )
    is UpdateProviderError.ApiKeyAlreadyInUse -> apiError(
        CommonApiErrorCodes.ALREADY_EXISTS,
        "API key already in use",
        "apiKeyId" to this.apiKeyId
    )
}

fun DeleteProviderError.toApiError(): ApiError = when (this) {
    is DeleteProviderError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Provider not found",
        "providerId" to this.id.toString()
    )
    is DeleteProviderError.ProviderInUse -> apiError(
        CommonApiErrorCodes.RESOURCE_IN_USE,
        "Provider is still in use by models",
        "providerId" to this.id.toString(),
        "modelNames" to this.modelNames.joinToString()
    )
}

fun UpdateProviderCredentialError.toApiError(): ApiError = when (this) {
    is UpdateProviderCredentialError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Provider not found",
        "providerId" to this.id.toString()
    )
    is UpdateProviderCredentialError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid credential input",
        "reason" to this.reason
    )
}
