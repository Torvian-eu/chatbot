package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Resource definitions for worker lifecycle endpoints.
 *
 * This resource intentionally includes registration only.
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
}
