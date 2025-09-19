package eu.torvian.chatbot.server.data.dao.error

/**
 * Errors that can occur when reading the owner of a resource.
 */
sealed interface GetOwnerError {
    /**
     * The requested resource (or its ownership row) was not found.
     *
     * @param resourceIdentifier String representation of the resource id (e.g., sessionId or alias).
     */
    data class ResourceNotFound(val resourceIdentifier: String) : GetOwnerError
}

/**
 * Errors that can occur when inserting/setting an ownership link in the owners table.
 *
 * Note: Many databases (and Exposed+SQLite) report only a generic foreign-key constraint
 * violation; therefore we expose a single ForeignKeyViolation bundling resource + referenced id.
 * Higher layers can perform additional existence checks if they need to report a more
 * specific error to clients.
 */
sealed interface SetOwnerError {
    /**
     * A foreign key violation occurred while creating the owner link. This indicates that
     * either the resource row or the referenced user row did not exist at insert time.
     *
     * @param resourceIdentifier String representation of the resource id.
     * @param referencedId The id of the referenced user that was inserted for the owner link.
     */
    data class ForeignKeyViolation(val resourceIdentifier: String, val referencedId: Long) : SetOwnerError

    /**
     * The owners table already contains a row for this resource (unique/PK violation).
     * Ownership for this resource is already set and cannot be created again via setOwner.
     */
    data object AlreadyOwned : SetOwnerError
}
