package eu.torvian.chatbot.server.worker.protocol.codec

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolJson
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encodes and decodes worker protocol envelopes for the server WebSocket transport.
 *
 * The codec reuses the shared worker JSON configuration so the server stays wire-compatible
 * with the worker client and future protocol additions.
 *
 * @property json Shared worker protocol JSON serializer instance.
 */
class WorkerServerWebSocketMessageCodec(
    private val json: Json = WorkerProtocolJson.json
) {
    /**
     * Serializes one worker protocol envelope into a text frame payload.
     *
     * @param message Worker protocol envelope to encode.
     * @return JSON text ready for transmission.
     */
    fun encode(message: WorkerProtocolMessage): String = json.encodeToString(message)

    /**
     * Decodes a text frame payload into a worker protocol envelope.
     *
     * @param text Raw WebSocket text payload.
     * @return Either the decoded envelope or a logical serialization error.
     */
    fun decode(text: String): Either<WorkerProtocolCodecError, WorkerProtocolMessage> {
        return try {
            json.decodeFromString<WorkerProtocolMessage>(text).right()
        } catch (exception: SerializationException) {
            WorkerProtocolCodecError.SerializationFailed(
                operation = "decode",
                targetType = WorkerProtocolMessage::class.simpleName ?: "WorkerProtocolMessage",
                details = exception.message
            ).left()
        }
    }
}


