package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Structured payload carried by a `command.message` envelope.
 *
 * Interaction correlation is handled by the envelope-level [WorkerProtocolMessage.interactionId],
 * not by command-specific identifiers in the payload.
 *
 * @property messageKind Machine-readable message subtype used by the target command session.
 * @property data Extensible structured payload interpreted by the target command session.
 */
@Serializable
data class WorkerCommandMessagePayload(
    val messageKind: String,
    val data: JsonObject
)

