package eu.torvian.chatbot.server.data.dao.error

/**
 * Errors that can occur when querying which groups have access to a resource.
 */
sealed interface GetAccessError {
    /**
     * The requested resource was not found (no such row).
     *
     * @param resourceIdentifier String representation of the resource id.
     */
    data class ResourceNotFound(val resourceIdentifier: String) : GetAccessError
}

/**
 * Errors that can occur when granting access to a resource for a group.
 *
 * As with ownership, FK violation details are noisy on some DBs; we use ForeignKeyViolation
 * to indicate either the resource or group was missing at insert time.
 */
sealed interface GrantAccessError {
    /**
     * Either the resource or the group did not exist (FK violation). Higher layers can run
     * existence checks to determine which one if they need a more precise error message.
     *
     * @param resourceIdentifier String representation of the resource id.
     * @param groupId The group id that was supplied when attempting to grant access.
     */
    data class ForeignKeyViolation(val resourceIdentifier: String, val groupId: Long) : GrantAccessError

    /**
     * The access entry already exists (unique constraint on (resource, group)).
     */
    data object AlreadyGranted : GrantAccessError
}

/**
 * Errors that can occur when revoking access to a resource from a group.
 */
sealed interface RevokeAccessError {
    /**
     * Either the resource or the group did not exist (FK violation discovered during delete or check).
     *
     * @param resourceIdentifier String representation of the resource id.
     * @param groupId The group id that was supplied when attempting to revoke access.
     */
    data class ForeignKeyViolation(val resourceIdentifier: String, val groupId: Long) : RevokeAccessError

    /**
     * The access entry did not exist (nothing to revoke). This is useful for idempotency decisions.
     */
    data object AccessNotGranted : RevokeAccessError
}
