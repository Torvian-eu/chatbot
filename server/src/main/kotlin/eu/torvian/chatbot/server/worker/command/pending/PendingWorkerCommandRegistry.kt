package eu.torvian.chatbot.server.worker.command.pending

import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchResult

/**
 * Registry for live worker command interactions that are still waiting for a terminal outcome.
 */
interface PendingWorkerCommandRegistry {
    /**
     * Registers a new pending command interaction.
     *
     * @param pendingCommand Pending command metadata and completion handle.
     * @return `true` when the interaction was registered, or `false` when the interaction ID was already in use.
     */
    fun register(pendingCommand: PendingWorkerCommand): Boolean

    /**
     * Looks up a pending command by its interaction identifier.
     *
     * @param interactionId Correlation identifier shared by the command lifecycle.
     * @return The matching pending command when still active, otherwise `null`.
     */
    fun get(interactionId: String): PendingWorkerCommand?

    /**
     * Records an inbound `command.accepted` lifecycle frame.
     *
     * The registry keeps the command pending so a later final response can still complete it.
     *
     * @param interactionId Correlation identifier shared by the command lifecycle.
     * @return The pending command when it exists, otherwise `null`.
     */
    fun markAccepted(interactionId: String): PendingWorkerCommand?

    /**
     * Completes a pending command with a terminal outcome.
     *
     * @param interactionId Correlation identifier shared by the command lifecycle.
     * @param outcome Final terminal result to deliver to the waiting dispatch caller.
     * @return `true` when the pending command was still active and was completed.
     */
    fun complete(interactionId: String, outcome: WorkerCommandDispatchResult): Boolean

    /**
     * Removes a pending command without completing it.
     *
     * This is primarily useful for local cleanup when a request cannot be sent at all.
     *
     * @param interactionId Correlation identifier shared by the command lifecycle.
     * @return `true` when the pending command existed and was removed.
     */
    fun remove(interactionId: String): Boolean

    /**
     * Completes every pending command associated with the specified worker.
     *
     * The first server-side policy is to fail pending dispatches explicitly when the worker socket
     * disappears, so callers do not wait for a timeout after a disconnect has already been observed.
     *
     * @param workerId Worker identifier whose live session disappeared.
     * @param reason Human-readable disconnect reason to attach to each pending command.
     * @return Number of pending commands that were completed.
     */
    fun failAllForWorker(workerId: Long, reason: String): Int
}


