package eu.torvian.chatbot.worker.protocol.handshake

/**
 * Minimal validated welcome data stored after a successful handshake.
 *
 * @property workerUid Worker identifier echoed by the server.
 * @property selectedProtocolVersion Protocol version selected by the server.
 * @property acceptedCapabilities Capabilities accepted by the server for this session.
 */
data class SessionWelcomeState(
    val workerUid: String,
    val selectedProtocolVersion: Int,
    val acceptedCapabilities: List<String>
)