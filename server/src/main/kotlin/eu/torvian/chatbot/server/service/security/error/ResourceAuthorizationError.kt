package eu.torvian.chatbot.server.service.security.error

import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.security.ResourceType

/**
 * Represents possible errors that can occur during resource authorization operations.
 */
sealed interface ResourceAuthorizationError {
    /** The requested resource (by id) was not found. */
    data class ResourceNotFound(val resourceType: ResourceType, val id: Long) : ResourceAuthorizationError

    /** The caller does not have the required access to the resource. */
    data class AccessDenied(
        val userId: Long,
        val resourceType: ResourceType,
        val id: Long,
        val accessMode: AccessMode
    ) : ResourceAuthorizationError

    /** The requested resource type is not supported. */
    data class UnsupportedResourceType(val resourceType: ResourceType) : ResourceAuthorizationError
}

/**
 * Extension function to convert ResourceAuthorizationError to ApiError for HTTP responses.
 */
fun ResourceAuthorizationError.toApiError(): ApiError = when (this) {
    is ResourceAuthorizationError.ResourceNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Resource not found",
        "resourceType" to resourceType.toString(),
        "id" to id.toString()
    )

    is ResourceAuthorizationError.AccessDenied -> apiError(
        CommonApiErrorCodes.PERMISSION_DENIED,
        "Access denied",
        "userId" to userId.toString(),
        "resourceType" to resourceType.toString(),
        "id" to id.toString(),
        "accessMode" to accessMode.toString()
    )

    is ResourceAuthorizationError.UnsupportedResourceType -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Unsupported resource type",
        "resourceType" to resourceType.toString()
    )
}
