package eu.torvian.chatbot.server.worker.protocol.handshake

/**
 * Logical failure raised while negotiating the initial worker hello handshake.
 */
sealed interface WorkerSessionHelloError {
    /**
     * Indicates that the inbound envelope is not a hello message.
     *
     * @property messageType Unexpected message type.
     */
    data class InvalidMessageType(
        val messageType: String
    ) : WorkerSessionHelloError

    /**
     * Indicates that the hello payload is missing or malformed.
     *
     * @property reason Human-readable decode or validation reason.
     */
    data class InvalidPayload(
        val reason: String
    ) : WorkerSessionHelloError

    /**
     * Indicates that no hello payload was included with the message.
     */
    data object MissingPayload : WorkerSessionHelloError

    /**
     * Indicates that the hello payload worker UID did not match the authenticated worker.
     *
     * @property workerId Authenticated worker identifier.
     * @property expectedWorkerUid Server-side worker UID from the authenticated worker context.
     * @property actualWorkerUid Worker UID announced by the client.
     */
    data class IdentityMismatch(
        val workerId: Long,
        val expectedWorkerUid: String,
        val actualWorkerUid: String
    ) : WorkerSessionHelloError

    /**
     * Indicates that the worker and server share no compatible protocol version.
     *
     * @property advertisedVersions Versions announced by the worker.
     * @property supportedVersions Versions currently supported by the server.
     */
    data class UnsupportedProtocolVersion(
        val advertisedVersions: List<Int>,
        val supportedVersions: List<Int>
    ) : WorkerSessionHelloError

    /**
     * Indicates that the welcome frame could not be written to the socket.
     *
     * @property reason Human-readable transport failure reason.
     */
    data class TransportFailed(
        val reason: String
    ) : WorkerSessionHelloError
}