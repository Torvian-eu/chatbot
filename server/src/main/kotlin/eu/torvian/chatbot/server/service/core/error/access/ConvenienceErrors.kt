package eu.torvian.chatbot.server.service.core.error.access

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when making a resource public.
 */
sealed interface MakeResourcePublicError {
    /**
     * The resource does not exist.
     */
    data class ResourceNotFound(val resourceId: Long) : MakeResourcePublicError

    /**
     * The "All Users" group does not exist in the system.
     * This should never happen in a properly configured system.
     */
    data object AllUsersGroupNotFound : MakeResourcePublicError

    /**
     * Resource or group does not exist (foreign key violation).
     */
    data class InvalidRelatedEntity(val resourceId: Long, val groupId: Long) : MakeResourcePublicError
}

/**
 * Errors that can occur when making a resource private.
 */
sealed interface MakeResourcePrivateError {
    /**
     * The resource does not exist.
     */
    data class ResourceNotFound(val resourceId: Long) : MakeResourcePrivateError

    /**
     * The "All Users" group does not exist in the system.
     */
    data object AllUsersGroupNotFound : MakeResourcePrivateError

    /**
     * Resource or group does not exist (foreign key violation).
     */
    data class InvalidRelatedEntity(val resourceId: Long, val groupId: Long) : MakeResourcePrivateError
}

/**
 * Errors that can occur when checking if a resource is public.
 */
sealed interface CheckResourcePublicError {
    /**
     * The resource does not exist.
     */
    data class ResourceNotFound(val resourceId: Long) : CheckResourcePublicError

    /**
     * The "All Users" group does not exist in the system.
     */
    data object AllUsersGroupNotFound : CheckResourcePublicError
}

// Extension functions to convert errors to API errors

fun MakeResourcePublicError.toApiError(): ApiError = when (this) {
    is MakeResourcePublicError.ResourceNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Resource not found",
        "resourceId" to resourceId.toString()
    )
    is MakeResourcePublicError.AllUsersGroupNotFound -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "System configuration error: All Users group not found"
    )
    is MakeResourcePublicError.InvalidRelatedEntity -> apiError(
        apiCode = CommonApiErrorCodes.INVALID_ARGUMENT,
        message = "Resource or group does not exist",
        "resourceId" to resourceId.toString(),
        "groupId" to groupId.toString()
    )
}

fun MakeResourcePrivateError.toApiError(): ApiError = when (this) {
    is MakeResourcePrivateError.ResourceNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Resource not found",
        "resourceId" to resourceId.toString()
    )
    is MakeResourcePrivateError.AllUsersGroupNotFound -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "System configuration error: All Users group not found"
    )
    is MakeResourcePrivateError.InvalidRelatedEntity -> apiError(
        apiCode = CommonApiErrorCodes.INVALID_ARGUMENT,
        message = "Resource or group does not exist",
        "resourceId" to resourceId.toString(),
        "groupId" to groupId.toString()
    )
}

fun CheckResourcePublicError.toApiError(): ApiError = when (this) {
    is CheckResourcePublicError.ResourceNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Resource not found",
        "resourceId" to resourceId.toString()
    )
    is CheckResourcePublicError.AllUsersGroupNotFound -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "System configuration error: All Users group not found"
    )
}