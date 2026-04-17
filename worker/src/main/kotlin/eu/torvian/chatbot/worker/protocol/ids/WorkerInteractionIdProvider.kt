package eu.torvian.chatbot.worker.protocol.ids

import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction

/**
 * Supplies logical worker protocol interaction identifiers.
 */
fun interface WorkerInteractionIdProvider {
    /**
     * Produces the next unique interaction identifier.
     *
     * @return Non-blank identifier suitable for [WorkerActiveInteraction.interactionId].
     */
    fun nextInteractionId(): String
}

