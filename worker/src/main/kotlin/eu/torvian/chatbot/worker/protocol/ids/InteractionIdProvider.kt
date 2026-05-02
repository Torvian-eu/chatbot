package eu.torvian.chatbot.worker.protocol.ids

import eu.torvian.chatbot.worker.protocol.interaction.Interaction

/**
 * Supplies logical worker protocol interaction identifiers.
 */
fun interface InteractionIdProvider {
    /**
     * Produces the next unique interaction identifier.
     *
     * @return Non-blank identifier suitable for [Interaction.interactionId].
     */
    fun nextInteractionId(): String
}

