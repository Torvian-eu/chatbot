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

fun DiscoverProviderModelsError.toApiError(): ApiError = when (this) {
    is DiscoverProviderModelsError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Provider not found",
        "providerId" to this.id.toString()
    )

    is DiscoverProviderModelsError.CredentialNotFound -> apiError(
        CommonApiErrorCodes.FAILED_PRECONDITION,
        "Provider credential is missing from secure storage",
        "alias" to this.alias
    )

    is DiscoverProviderModelsError.AuthenticationFailed -> apiError(
        CommonApiErrorCodes.INVALID_CREDENTIALS,
        "Provider authentication failed",
        "reason" to this.reason
    )

    is DiscoverProviderModelsError.InvalidConfiguration -> apiError(
        CommonApiErrorCodes.FAILED_PRECONDITION,
        "Provider configuration is invalid for model discovery",
        "reason" to this.reason
    )

    is DiscoverProviderModelsError.ProviderApiError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Provider API returned an error during model discovery",
        "statusCode" to this.statusCode.toString(),
        "reason" to this.reason
    )

    is DiscoverProviderModelsError.InvalidProviderResponse -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Provider returned an invalid discovery response",
        "reason" to this.reason
    )

    is DiscoverProviderModelsError.ProviderUnavailable -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Provider is currently unavailable",
        "reason" to this.reason
    )
}

fun TestProviderConnectionError.toApiError(): ApiError = when (this) {
    is TestProviderConnectionError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid provider connection test input",
        "reason" to this.reason
    )

    is TestProviderConnectionError.AuthenticationFailed -> apiError(
        CommonApiErrorCodes.INVALID_CREDENTIALS,
        "Provider authentication failed",
        "reason" to this.reason
    )

    is TestProviderConnectionError.InvalidConfiguration -> apiError(
        CommonApiErrorCodes.FAILED_PRECONDITION,
        "Provider configuration is invalid for connection test",
        "reason" to this.reason
    )

    is TestProviderConnectionError.ProviderApiError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Provider API returned an error during connection test",
        "statusCode" to this.statusCode.toString(),
        "reason" to this.reason
    )

    is TestProviderConnectionError.InvalidProviderResponse -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Provider returned an invalid response during connection test",
        "reason" to this.reason
    )

    is TestProviderConnectionError.ProviderUnavailable -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Provider is currently unavailable",
        "reason" to this.reason
    )
}

