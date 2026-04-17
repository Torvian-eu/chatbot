package eu.torvian.chatbot.worker.protocol.registry

import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [WorkerActiveInteractionRegistry] implementation.
 */
class InMemoryWorkerActiveInteractionRegistry : WorkerActiveInteractionRegistry {
    /**
     * Active interactions keyed by interaction identifier.
     */
    private val interactionsById: ConcurrentHashMap<String, WorkerActiveInteraction> = ConcurrentHashMap()

    /**
     * @param interaction Interaction to register.
     * @return `true` when inserted; `false` when another interaction already uses the same interaction identifier.
     */
    override fun register(interaction: WorkerActiveInteraction): Boolean {
        return interactionsById.putIfAbsent(interaction.interactionId, interaction) == null
    }

    /**
     * @param interactionId Interaction identifier to resolve.
     * @return Active interaction when present; otherwise `null`.
     */
    override fun get(interactionId: String): WorkerActiveInteraction? {
        return interactionsById[interactionId]
    }

    /**
     * @param interactionId Interaction identifier to remove.
     */
    override fun remove(interactionId: String) {
        interactionsById.remove(interactionId)
    }
}
