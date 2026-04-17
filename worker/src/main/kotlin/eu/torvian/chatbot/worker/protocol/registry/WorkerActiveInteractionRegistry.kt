package eu.torvian.chatbot.worker.protocol.registry

import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction

/**
 * Registry for active interactions keyed by interaction identifier.
 */
interface WorkerActiveInteractionRegistry {
    /**
     * Registers a newly created active interaction.
     *
     * @param interaction Interaction to register.
     * @return `true` when registration succeeds, `false` when the interaction ID is already active.
     */
    fun register(interaction: WorkerActiveInteraction): Boolean

    /**
     * Looks up an active interaction by interaction identifier.
     *
     * @param interactionId Interaction identifier to resolve.
     * @return Active interaction when present; otherwise `null`.
     */
    fun get(interactionId: String): WorkerActiveInteraction?

    /**
     * Unregisters an active interaction by interaction identifier.
     *
     * @param interactionId Interaction identifier to remove.
     */
    fun remove(interactionId: String)
}
