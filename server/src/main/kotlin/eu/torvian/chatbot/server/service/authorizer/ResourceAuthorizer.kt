package eu.torvian.chatbot.server.service.authorizer

import arrow.core.Either

/**
 * Pluggable authorizer responsible for enforcing resource-scoped access rules.
 *
 * Implementations encapsulate the policy for a single resource type (for example
 * "group", "session" or "llm_model"). They must return domain-level errors
 * using [ResourceAuthorizerError] when access is denied or the resource is missing.
 */
interface ResourceAuthorizer {
    /** The resource type handled by this authorizer (e.g. "group"). */
    val resourceType: String

    /**
     * Enforce that [userId] has the requested [accessMode] for [resourceId].
     *
     * Return Right(Unit) on success. Return Left(ResourceNotFound) when the
     * resource (or its ownership row) does not exist. Return Left(AccessDenied)
     * when the user is not allowed to perform the requested operation.
     *
     * Throw exceptions for technical failures (DB connectivity etc.); those are
     * not encoded in domain errors.
     */
    suspend fun requireAccess(
        userId: Long,
        resourceId: Long,
        accessMode: AccessMode
    ): Either<ResourceAuthorizerError, Unit>
}
