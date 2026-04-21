package eu.torvian.chatbot.worker.runtime

import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapError

/**
 * Logical errors that occur during worker runtime startup and operation.
 *
 * This unified error type represents all possible failure modes during:
 * - Worker authentication
 * - Assigned configuration bootstrap
 * - Connection cycle management
 *
 * By modeling both auth and bootstrap errors at the runtime level, callers can distinguish
 * between different classes of startup failures and respond appropriately.
 */
sealed interface WorkerRuntimeError {
    /**
     * Worker authentication failed.
     *
     * Indicates a failure in token acquisition, challenge flow, or token persistence.
     *
     * @property error The underlying auth manager error.
     */
    data class Auth(val error: WorkerAuthManagerError) : WorkerRuntimeError

    /**
     * Assigned MCP server configuration bootstrap failed.
     *
     * Indicates a failure in fetching or storing assigned server configurations.
     * This is a hard blocker for worker operation; the session cannot proceed without it.
     *
     * @property error The underlying bootstrap error.
     */
    data class AssignedConfigBootstrap(val error: AssignedConfigBootstrapError) : WorkerRuntimeError
}