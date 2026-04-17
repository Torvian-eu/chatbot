package eu.torvian.chatbot.server.worker.session

/**
 * In-memory registry for active authenticated worker WebSocket sessions.
 */
interface WorkerSessionRegistry {
    /**
     * Registers a ready worker session and returns the replaced session, if any.
     *
     * The registry is keyed by the authenticated worker identifier so reconnects naturally
     * replace an existing live connection for the same worker.
     *
     * @param session Ready worker session to store.
     * @return Previously registered session for the same worker, if one existed.
     */
    fun register(session: ConnectedWorkerSession): ConnectedWorkerSession?

    /**
     * Looks up the currently connected session for a worker.
     *
     * @param workerId Worker identifier.
     * @return Active session when present; otherwise `null`.
     */
    fun get(workerId: Long): ConnectedWorkerSession?

    /**
     * Removes a worker session from the registry.
     *
     * When [session] is provided, removal only succeeds if it is still the active instance for
     * the worker. This prevents a disconnected stale socket from removing a newer replacement.
     *
     * @param workerId Worker identifier.
     * @param session Optional session instance that must match the active entry.
     * @return `true` when an entry was removed.
     */
    fun remove(workerId: Long, session: ConnectedWorkerSession? = null): Boolean
}

