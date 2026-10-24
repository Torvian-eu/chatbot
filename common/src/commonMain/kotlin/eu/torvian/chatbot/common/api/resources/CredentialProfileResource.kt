package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Resource definitions for credential profile management.
 *
 * Profiles are bound to one worker, so every user-facing read is scoped by a worker id and the worker
 * assignment is carried in the request body rather than the path.
 */
@Resource("credential-profiles")
class CredentialProfileResource(
    val parent: Api = Api(),
    /**
     * Worker whose profiles are wanted. Omitted for single-profile operations.
     */
    val workerId: Long? = null,
) {
    /**
     * Resource for one credential profile.
     *
     * Endpoint: /api/v1/credential-profiles/{id}
     *
     * @property parent Parent resource carrying the API prefix and optional worker filter.
     * @property id Profile identifier.
     */
    @Resource("{id}")
    class ById(
        val parent: CredentialProfileResource = CredentialProfileResource(),
        val id: Long,
    )
}
