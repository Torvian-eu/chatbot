package eu.torvian.chatbot.server.service.core.error.agent

/**
 * Logical errors that can occur while building an [eu.torvian.chatbot.common.models.agent.AgentSpawnRequest]
 * from an operator-tool call.
 *
 * These errors are deliberately user-facing: the orchestrator converts them into tool-level ERROR
 * results so the calling LLM hears a clear message (e.g. "agent role id 12 not found") instead of
 * crashing the turn.
 */
sealed class SpawnRequestBuildError {

    /**
     * The tool-call input could not be decoded or was missing a required parameter.
     *
     * @property reason Human-readable description of the malformed input.
     */
    data class InvalidInput(val reason: String) : SpawnRequestBuildError()

    /**
     * The requested agent role does not exist or is not owned by the spawning user.
     *
     * Both cases collapse into this one shape, so the request cannot tell a foreign role apart from
     * a non-existent id (no existence leak).
     *
     * @property roleId The role id that was requested.
     */
    data class RoleNotFound(val roleId: Long) : SpawnRequestBuildError()

    /**
     * The source role is not allowed to spawn the requested target role.
     *
     * Raised when the target exists and is owned by the caller but is absent from the source role's
     * spawn allow-list, and when the source role itself cannot be loaded.
     *
     * @property roleId The target role id supplied by the model.
     */
    data class RoleNotAllowed(val roleId: Long) : SpawnRequestBuildError()
}
