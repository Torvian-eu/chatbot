package eu.torvian.chatbot.worker.protocol.interaction

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage

/**
 * Runtime representation of one active protocol interaction keyed by envelope `interactionId`.
 */
interface Interaction {
    /**
     * Stable interaction identifier that keys this interaction in the active-interaction registry.
     *
     * This identifier comes from the envelope-level [WorkerProtocolMessage.interactionId]
     * and groups all messages belonging to the same logical interaction.
     */
    val interactionId: String

    /**
     * Starts the interaction lifecycle.
     */
    suspend fun start()

    /**
     * Delivers one protocol envelope to this active interaction.
     *
     * @param message Inbound protocol envelope addressed to this interaction.
     */
    suspend fun onMessage(message: WorkerProtocolMessage)
}


