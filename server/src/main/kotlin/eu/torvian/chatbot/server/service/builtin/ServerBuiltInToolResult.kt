package eu.torvian.chatbot.server.service.builtin

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Serializes a handler result with the shared JSON codec.
 *
 * @param json The shared JSON codec used for encoding.
 * @param value The serializable value to encode.
 * @return Either a serialization-failure handler error or the encoded JSON string.
 */
internal inline fun <reified T> encodeResult(
    json: Json,
    value: T
): Either<ServerBuiltInToolHandlerError, String> =
    runCatching { json.encodeToString(value) }.fold(
        onSuccess = { it.right() },
        onFailure = { error ->
            ServerBuiltInToolHandlerError.OperationFailed(
                "serialization_failed",
                "Failed to serialize tool result: ${error.message}"
            ).left()
        }
    )

/**
 * Serializes a [JsonElement] with the shared JSON codec.
 *
 * Used for handlers that assemble their output from raw [JsonElement]s rather than a dedicated
 * serializable DTO.
 *
 * @param json The shared JSON codec used for encoding.
 * @param element The [JsonElement] to encode.
 * @return Either a serialization-failure handler error or the encoded JSON string.
 */
internal fun encodeJsonElement(
    json: Json,
    element: JsonElement
): Either<ServerBuiltInToolHandlerError, String> =
    runCatching { json.encodeToString(JsonElement.serializer(), element) }.fold(
        onSuccess = { it.right() },
        onFailure = { error ->
            ServerBuiltInToolHandlerError.OperationFailed(
                "serialization_failed",
                "Failed to serialize tool result: ${error.message}"
            ).left()
        }
    )
