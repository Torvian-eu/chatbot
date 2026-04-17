package eu.torvian.chatbot.common.models.api.worker.protocol.constants

/**
 * Protocol-level WebSocket envelope message types.
 */
object WorkerProtocolMessageTypes {
    /**
     * Message type used by workers to start session handshake negotiation.
     */
    const val SESSION_HELLO = "session.hello"

    /**
     * Message type used by servers to welcome workers after successful handshake.
     */
    const val SESSION_WELCOME = "session.welcome"

    /**
     * Message type used for server-to-worker command dispatch.
     */
    const val COMMAND_REQUEST = "command.request"

    /**
     * Message type used for follow-up server-to-worker messages that target an active command session.
     */
    const val COMMAND_MESSAGE = "command.message"

    /**
     * Message type reserved for future command-cancellation requests.
     */
    const val COMMAND_CANCEL = "command.cancel"

    /**
     * Message type used when a worker acknowledges command acceptance.
     */
    const val COMMAND_ACCEPTED = "command.accepted"

    /**
     * Message type used when a worker reports command progress.
     */
    const val COMMAND_PROGRESS = "command.progress"

    /**
     * Message type used when a worker reports final command completion.
     *
     * The final outcome is described inside `payload.status`.
     */
    const val COMMAND_RESULT = "command.result"

    /**
     * Message type used when a worker rejects a command request.
     *
     * The reason for rejection can be described inside `payload.reason`.
     */
    const val COMMAND_REJECTED = "command.rejected"

    /**
     * Message type used for worker heartbeat pings.
     */
    const val HEARTBEAT_PING = "heartbeat.ping"

    /**
     * Message type used for worker heartbeat pongs.
     */
    const val HEARTBEAT_PONG = "heartbeat.pong"
}