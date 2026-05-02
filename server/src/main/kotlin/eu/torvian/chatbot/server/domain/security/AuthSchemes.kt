package eu.torvian.chatbot.server.domain.security

/**
 * Constants for authentication scheme names used throughout the application.
 * 
 * These constants define the names of authentication schemes configured in Ktor
 * and referenced in route protection and authentication middleware.
 */
object AuthSchemes {
    /**
     * JWT authentication scheme for regular user authentication.
     * 
     * This scheme is used for protecting API routes that require user authentication.
     * Tokens are validated against the configured JWT settings and user sessions.
     */
    const val USER_JWT = "user-jwt"
    
    /**
     * JWT authentication scheme for administrative operations.
     * 
     * This scheme could be used in the future for admin-specific authentication
     * with different token validation rules or expiration times.
     */
    const val ADMIN_JWT = "admin-jwt"

    /**
     * JWT authentication scheme for worker service principals.
     */
    const val WORKER_JWT = "worker-jwt"
}
