package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Resource definitions for credential secret management.
 *
 * A secret is bound to one worker and its value never leaves the server after the write, so reads
 * return metadata only.
 */
@Resource("credential-secrets")
class CredentialSecretResource(
    val parent: Api = Api(),
    /**
     * Worker whose secrets are wanted. Omitted for single-secret operations.
     */
    val workerId: Long? = null,
) {
    /**
     * Resource for one credential secret.
     *
     * Endpoint: /api/v1/credential-secrets/{id}
     *
     * @property parent Parent resource carrying the API prefix and optional worker filter.
     * @property id Secret identifier.
     */
    @Resource("{id}")
    class ById(
        val parent: CredentialSecretResource = CredentialSecretResource(),
        val id: Long,
    )
}

/**
 * Resource definitions for the worker-authenticated credential bootstrap.
 */
@Resource("credentials")
class WorkerCredentialResource(val parent: Api = Api()) {
    /**
     * Everything assigned to the calling worker.
     *
     * Endpoint: GET /api/v1/credentials/assigned
     *
     * @property parent Parent resource carrying the API prefix.
     */
    @Resource("assigned")
    class Assigned(val parent: WorkerCredentialResource = WorkerCredentialResource())
}
