package eu.torvian.chatbot.server.service.core.error.model

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.apiError

/**
 * Extension functions to convert model service errors to API errors.
 */

fun AddModelError.toApiError(): ApiError = when (this) {
    is AddModelError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid model input",
        "reason" to this.reason
    )
    is AddModelError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Provider not found for model",
        "providerId" to this.providerId.toString()
    )
    is AddModelError.ModelNameAlreadyExists -> apiError(
        CommonApiErrorCodes.ALREADY_EXISTS,
        "Model name already exists",
        "name" to this.name
    )
    is AddModelError.OwnershipError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Failed to set model ownership",
        "reason" to this.reason
    )
}

fun GetModelError.toApiError(): ApiError = when (this) {
    is GetModelError.ModelNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Model not found",
        "modelId" to this.id.toString()
    )
}

fun UpdateModelError.toApiError(): ApiError = when (this) {
    is UpdateModelError.ModelNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Model not found",
        "modelId" to this.id.toString()
    )
    is UpdateModelError.InvalidInput -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid model input",
        "reason" to this.reason
    )
    is UpdateModelError.ProviderNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Provider not found",
        "providerId" to this.providerId.toString()
    )
    is UpdateModelError.ModelNameAlreadyExists -> apiError(
        CommonApiErrorCodes.ALREADY_EXISTS,
        "Model name already exists",
        "name" to this.name
    )
}

fun DeleteModelError.toApiError(): ApiError = when (this) {
    is DeleteModelError.ModelNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Model not found",
        "modelId" to this.id.toString()
    )
}
