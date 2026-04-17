package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import kotlinx.serialization.Serializable

/**
 * Payload emitted when the worker accepts a command request.
 *
 * This payload is empty; interaction correlation comes from the envelope-level
 * [WorkerProtocolMessage.interactionId].
 */
@Serializable
data object WorkerCommandAcceptedPayload

