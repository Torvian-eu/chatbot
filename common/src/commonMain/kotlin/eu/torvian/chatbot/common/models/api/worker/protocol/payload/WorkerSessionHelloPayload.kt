package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import kotlinx.serialization.Serializable

/**
 * Payload sent by a worker to start protocol session negotiation.
 *
 * @property workerUid Stable worker identifier expected by the server.
 * @property capabilities Worker-declared capabilities that can be enabled for this session.
 * @property supportedProtocolVersions Protocol versions this worker can speak.
 * @property workerVersion Optional worker build version used for diagnostics and rollout checks.
 */
@Serializable
data class WorkerSessionHelloPayload(
    val workerUid: String,
    val capabilities: List<String>,
    val supportedProtocolVersions: List<Int>,
    val workerVersion: String? = null
)

