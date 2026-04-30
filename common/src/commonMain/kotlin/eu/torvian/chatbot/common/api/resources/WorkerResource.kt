package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Resource definitions for worker lifecycle endpoints.
 *
 * This resource includes registration and listing of workers owned by the authenticated user.
 * Delegation/job endpoints are deferred for a later phase.
 *
 * @property parent Parent API resource (`/api/v1`).
 */
@Resource("workers")
class WorkerResource(val parent: Api = Api()) {
    /**
     * POST /api/v1/workers/register
     *
     * @property parent Parent worker resource.
     */
    @Resource("register")
    class Register(val parent: WorkerResource = WorkerResource())

    /**
     * Resource for a specific worker by ID.
     *
     * Endpoint: /api/v1/workers/{id}
     *
     * @property parent Parent worker resource.
     * @property id Worker identifier.
     */
    @Resource("{id}")
    class Id(val parent: WorkerResource = WorkerResource(), val id: Long)
}
