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

    /**
     * The source role is not allowed to spawn the requested target role.
     *
     * @property roleName The target role name supplied by the model.
     */
    data class RoleNotAllowed(val roleName: String) : SpawnRequestBuildError()

    /**
     * The spawned role does not belong to the calling session's project scope, so attaching it to
     * the spawned session would violate the Session Legality Invariant (a project-attached session
     * may only spawn roles within that project, a project-less session only unassociated roles).
     * Reaching this error means the role escaped the project-scoped name lookup; the builder never
     * falls back to a different project of the role.
     *
     * @property roleName The target role name supplied by the model.
     * @property projectId The calling session's project scope, or `null` when the session has no
     *            project (in which case only unassociated roles are spawnable).
     */
    data class RoleNotInProject(val roleName: String, val projectId: Long?) : SpawnRequestBuildError()
}
