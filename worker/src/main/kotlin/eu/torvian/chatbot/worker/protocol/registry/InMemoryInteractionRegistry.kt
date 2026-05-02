package eu.torvian.chatbot.worker.protocol.registry

import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [InteractionRegistry] implementation.
 */
class InMemoryInteractionRegistry : InteractionRegistry {
    /**
     * Active interactions keyed by interaction identifier.
     */
    private val interactionsById: ConcurrentHashMap<String, Interaction> = ConcurrentHashMap()

    override fun register(interaction: Interaction): Boolean {
        return interactionsById.putIfAbsent(interaction.interactionId, interaction) == null
    }

    override fun get(interactionId: String): Interaction? {
        return interactionsById[interactionId]
    }

    override fun remove(interactionId: String) {
        interactionsById.remove(interactionId)
    }
}
