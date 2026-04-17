package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import kotlinx.serialization.Serializable

/**
 * Payload sent by a server to confirm successful worker session negotiation.
 *
 * @property workerUid Worker identifier echoed by the server for correlation checks.
 * @property selectedProtocolVersion Protocol version selected by the server for this session.
 * @property acceptedCapabilities Capability identifiers accepted for this session.
 */
@Serializable
data class WorkerSessionWelcomePayload(
    val workerUid: String,
    val selectedProtocolVersion: Int,
    val acceptedCapabilities: List<String>
)

