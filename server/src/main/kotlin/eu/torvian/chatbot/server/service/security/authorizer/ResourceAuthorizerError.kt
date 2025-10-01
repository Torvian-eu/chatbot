package eu.torvian.chatbot.server.service.security.authorizer

/**
 * Domain errors returned by resource authorizers.
 */
sealed interface ResourceAuthorizerError {
    /** The requested resource (by id) was not found. */
    data class ResourceNotFound(val id: Long) : ResourceAuthorizerError

    /** The caller does not have the required access to the resource. */
    data class AccessDenied(val reason: String = "Access denied") : ResourceAuthorizerError
}
