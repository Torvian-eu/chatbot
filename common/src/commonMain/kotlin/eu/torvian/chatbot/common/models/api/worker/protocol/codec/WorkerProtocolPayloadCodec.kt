package eu.torvian.chatbot.common.models.api.worker.protocol.codec

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Encodes a protocol payload value into JSON using the shared worker codec.
 *
 * @param value Serializable payload value to encode.
 * @param targetType Human-readable payload type used in diagnostics.
 * @return Either the encoded JSON object or a logical codec error.
 */
inline fun <reified T> encodeProtocolPayload(
    value: T,
    targetType: String
): Either<WorkerProtocolCodecError, JsonObject> = either {
    try {
        when (val encoded = WorkerProtocolJson.json.encodeToJsonElement(value)) {
            is JsonObject -> encoded
            else -> {
                // Payloads are protocol objects by contract, so any other JSON shape is a local bug.
                raise(
                    WorkerProtocolCodecError.SerializationFailed(
                        operation = "encode",
                        targetType = targetType,
                        details = "Encoded payload was not a JSON object"
                    )
                )
            }
        }
    } catch (exception: SerializationException) {
        raise(
            WorkerProtocolCodecError.SerializationFailed(
                operation = "encode",
                targetType = targetType,
                details = exception.message
            )
        )
    }
}

/**
 * Decodes a protocol payload value from a JSON object using the shared worker codec.
 *
 * @param payload JSON object to decode.
 * @param targetType Human-readable payload type used in diagnostics.
 * @return Either the decoded value or a logical codec error.
 */
inline fun <reified T> decodeProtocolPayload(
    payload: JsonObject,
    targetType: String
): Either<WorkerProtocolCodecError, T> = either {
    try {
        WorkerProtocolJson.json.decodeFromJsonElement(payload)
    } catch (exception: SerializationException) {
        raise(
            WorkerProtocolCodecError.SerializationFailed(
                operation = "decode",
                targetType = targetType,
                details = exception.message
            )
        )
    } catch (exception: IllegalArgumentException) {
        raise(
            WorkerProtocolCodecError.SerializationFailed(
                operation = "decode",
                targetType = targetType,
                details = exception.message
            )
        )
    }
}



