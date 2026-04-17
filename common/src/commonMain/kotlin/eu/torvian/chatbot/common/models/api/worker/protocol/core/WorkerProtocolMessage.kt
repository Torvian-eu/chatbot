package eu.torvian.chatbot.common.models.api.worker.protocol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Generic worker WebSocket envelope.
 *
 * The envelope stays intentionally small and transport-oriented:
 * - `id` identifies the individual WebSocket frame/message
 * - `type` identifies the protocol message category
 * - `replyTo` optionally links this message to another message
 * - `timestamp` supports observability and diagnostics
 * - `protocolVersion` supports protocol evolution
 * - `interactionId` groups all messages belonging to the same logical interaction
 * - `payload` contains message-specific structured JSON
 *
 * Protocol-specific details such as `commandType`, `status`,
 * and the command-specific `data` payload live inside [payload].
 *
 * The `interactionId` is a generic correlation identifier distinct from message `id` and `replyTo`.
 * It is not command-specific; it groups all messages that are part of the same logical interaction
 * (for example, a complete command.request/command.accepted/command.result flow).
 *
 * @property id Unique message identifier for transport-level traceability.
 * @property type Message category discriminator (for example `session.hello` or `command.request`).
 * @property replyTo Optional message ID this frame is replying to.
 * @property timestamp Creation time of this envelope for observability and ordering diagnostics.
 * @property protocolVersion Worker protocol version used by this message.
 * @property interactionId Generic correlation identifier shared by all messages in the same logical interaction.
 * @property payload Flexible JSON payload containing protocol-specific message content.
 */
@Serializable
data class WorkerProtocolMessage(
    val id: String,
    val type: String,
    val replyTo: String? = null,
    val timestamp: Instant = Clock.System.now(),
    val protocolVersion: Int = WorkerProtocolVersion.CURRENT,
    val interactionId: String,
    val payload: JsonObject? = null
)