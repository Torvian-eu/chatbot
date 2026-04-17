package eu.torvian.chatbot.server.worker.session

/**
 * Internal lifecycle state for a live worker socket.
 */
sealed interface WorkerSessionState {
    /**
     * Indicates the socket is open but still waiting for the initial hello exchange.
     */
    data object Connected : WorkerSessionState

    /**
     * Indicates the socket completed hello/welcome negotiation and can receive commands.
     *
     * @property selectedProtocolVersion Protocol version negotiated with the worker for this socket.
     * @property acceptedCapabilities Normalized capability keys accepted from the worker hello payload.
     */
    data class Ready(
        val selectedProtocolVersion: Int,
        val acceptedCapabilities: List<String>
    ) : WorkerSessionState

    /**
     * Indicates the socket has been closed or is in the process of shutting down.
     *
     * @property reason Optional diagnostic reason for the terminal state.
     */
    data class Closed(
        val reason: String? = null
    ) : WorkerSessionState
}

