package eu.torvian.chatbot.server.service.core.error.agent

/**
 * Logical errors that can occur while building an [eu.torvian.chatbot.common.models.agent.AgentSpawnRequest]
 * from an operator-tool call.
 *
 * These errors are deliberately user-facing: the orchestrator converts them into tool-level ERROR
 * results so the calling LLM hears a clear message (e.g. "role 'x' not found") instead of crashing
 * the turn.
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
     * @property roleName The role name that was requested.
     */
    data class RoleNotFound(val roleName: String) : SpawnRequestBuildError()
}
