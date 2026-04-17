package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Payload emitted when the worker completes a command.
 *
 * Interaction correlation is handled by the envelope-level [WorkerProtocolMessage.interactionId],
 * not by command-specific identifiers in the payload.
 *
 * @property status Final command status such as success or error.
 * @property data Command result data serialized as JSON.
 */
@Serializable
data class WorkerCommandResultPayload(
    val status: String,
    val data: JsonObject
)

