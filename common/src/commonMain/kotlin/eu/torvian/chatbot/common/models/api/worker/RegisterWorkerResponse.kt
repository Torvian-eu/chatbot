package eu.torvian.chatbot.common.models.api.worker

import eu.torvian.chatbot.common.models.worker.Worker
import kotlinx.serialization.Serializable

/**
 * Registration response containing created worker metadata.
 *
 * @property worker Newly created worker.
 */
@Serializable
data class RegisterWorkerResponse(
    val worker: Worker
)

