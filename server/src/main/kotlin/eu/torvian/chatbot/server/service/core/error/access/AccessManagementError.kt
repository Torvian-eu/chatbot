package eu.torvian.chatbot.server.service.core.error.access

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Domain errors for resource access management operations.
 */

/**
 * Errors that can occur when granting access to a resource for a group.
 */
sealed class GrantResourceAccessError {
    /**
     * The access entry already exists (unique constraint on (resource, group)).
     */
    data class AlreadyGranted(val resourceId: Long, val groupId: Long, val accessMode: String) :
        GrantResourceAccessError()

    /**
     * Resource or group does not exist (foreign key violation).
     */
    data class InvalidRelatedEntity(val resourceId: Long, val groupId: Long) : GrantResourceAccessError()
}

sealed class RevokeResourceAccessError {
    /**
     * The access entry did not exist (nothing to revoke).
     */
    data class AccessNotFound(val resourceId: Long, val groupId: Long, val accessMode: String) :
        RevokeResourceAccessError()
}

sealed class GetResourceAccessError {
    /**
     * The resource does not exist.
     */
    data class ResourceNotFound(val resourceId: Long) : GetResourceAccessError()

    /**
     * The owner of the resource could not be found.
     */
    data class OwnerNotFound(val resourceId: Long) : GetResourceAccessError()
}

// Extension functions to convert to API errors
fun GrantResourceAccessError.toApiError(): ApiError = when (this) {
    is GrantResourceAccessError.AlreadyGranted -> apiError(
        apiCode = CommonApiErrorCodes.ALREADY_EXISTS,
        message = "Access already granted",
        "resourceId" to resourceId.toString(),
        "groupId" to groupId.toString(),
        "accessMode" to accessMode
    )

    is GrantResourceAccessError.InvalidRelatedEntity -> apiError(
        apiCode = CommonApiErrorCodes.INVALID_ARGUMENT,
        message = "Resource or group does not exist",
        "resourceId" to resourceId.toString(),
        "groupId" to groupId.toString()
    )
}

fun RevokeResourceAccessError.toApiError(): ApiError = when (this) {
    is RevokeResourceAccessError.AccessNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Access not found for this group and access mode",
        "resourceId" to resourceId.toString(),
        "groupId" to groupId.toString(),
        "accessMode" to accessMode
    )
}

fun GetResourceAccessError.toApiError(): ApiError = when (this) {
    is GetResourceAccessError.ResourceNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Resource not found",
        "resourceId" to resourceId.toString()
    )

    is GetResourceAccessError.OwnerNotFound -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "Failed to get owner",
        "resourceId" to resourceId.toString()
    )
}