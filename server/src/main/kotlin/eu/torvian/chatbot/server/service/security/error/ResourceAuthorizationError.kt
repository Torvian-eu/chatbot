package eu.torvian.chatbot.server.service.security.error

/**
 * Represents possible errors that can occur during resource authorization operations.
 */
sealed interface ResourceAuthorizationError {
    /** The requested resource (by id) was not found. */
    data class ResourceNotFound(val resourceType: String, val id: Long) : ResourceAuthorizationError

    /** The caller does not have the required access to the resource. */
    data class PermissionDenied(val reason: String) : ResourceAuthorizationError
}

