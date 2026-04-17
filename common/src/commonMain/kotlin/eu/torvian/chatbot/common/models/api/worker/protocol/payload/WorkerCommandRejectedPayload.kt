package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Payload emitted when the worker rejects a command request.
 *
 * Interaction correlation is handled by the envelope-level [WorkerProtocolMessage.interactionId],
 * not by command-specific identifiers in the payload.
 *
 * @property commandType Command type that was rejected when it is known.
 * @property reasonCode Machine-readable rejection reason.
 * @property message Human-readable description of why the command was rejected.
 * @property details Optional structured diagnostics for the rejection.
 */
@Serializable
data class WorkerCommandRejectedPayload(
    val commandType: String? = null,
    val reasonCode: String,
    val message: String,
    val details: JsonObject? = null
)

