package eu.torvian.chatbot.worker.protocol.handshake

/**
 * Snapshot of one worker hello/welcome handshake lifecycle.
 */
sealed interface WorkerSessionHandshakeState {
    /**
     * Marker state used after hello is emitted and before welcome validation completes.
     */
    data object Pending : WorkerSessionHandshakeState

    /**
     * Successful handshake state carrying negotiated session data.
     *
     * @property welcome Server-confirmed welcome data validated by the worker.
     */
    data class Succeeded(
        val welcome: WorkerSessionWelcomeState
    ) : WorkerSessionHandshakeState

    /**
     * Failed handshake state with a logical, user-actionable reason.
     *
     * @property reason Human-readable failure reason suitable for diagnostics.
     */
    data class Failed(
        val reason: String
    ) : WorkerSessionHandshakeState
}

/**
 * Minimal validated welcome data stored after a successful handshake.
 *
 * @property workerUid Worker identifier echoed by the server.
 * @property selectedProtocolVersion Protocol version selected by the server.
 * @property acceptedCapabilities Capabilities accepted by the server for this session.
 */
data class WorkerSessionWelcomeState(
    val workerUid: String,
    val selectedProtocolVersion: Int,
    val acceptedCapabilities: List<String>
)

