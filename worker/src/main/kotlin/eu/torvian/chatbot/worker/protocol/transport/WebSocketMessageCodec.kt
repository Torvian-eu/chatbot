package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolJson
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Encodes and decodes worker protocol envelopes for WebSocket text frames.
 *
 * @property json Shared worker protocol JSON codec.
 */
class WebSocketMessageCodec(
    private val json: Json = WorkerProtocolJson.json
) {
    /**
     * Encodes one worker protocol envelope into a WebSocket text payload.
     *
     * @param message Worker protocol envelope to encode.
     * @return Serialized JSON text payload.
     */
    fun encode(message: WorkerProtocolMessage): String {
        return json.encodeToString(message)
    }

    /**
     * Decodes one WebSocket text payload into a worker protocol envelope.
     *
     * @param text Raw WebSocket text payload.
     * @return Decoded message on success, or a logical decode failure.
     */
    fun decode(text: String): Either<WebSocketMessageCodecError, WorkerProtocolMessage> {
        return try {
            json.decodeFromString<WorkerProtocolMessage>(text).right()
        } catch (error: SerializationException) {
            WebSocketMessageCodecError.DecodeFailed(
                reason = error.message ?: error::class.simpleName.orEmpty()
            ).left()
        }
    }
}

