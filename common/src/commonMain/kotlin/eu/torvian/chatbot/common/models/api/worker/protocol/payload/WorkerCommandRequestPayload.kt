package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Structured payload carried by a `command.request` envelope.
 *
 * The payload separates command metadata from command-specific data so the
 * worker can route by [commandType] before decoding [data].
 *
 * Interaction correlation is handled by the envelope-level [WorkerProtocolMessage.interactionId],
 * not by command-specific identifiers in the payload.
 *
 * @property commandType Machine-readable command type for routing.
 * @property data Command-specific JSON payload.
 */
@Serializable
data class WorkerCommandRequestPayload(
    val commandType: String,
    val data: JsonObject
)

