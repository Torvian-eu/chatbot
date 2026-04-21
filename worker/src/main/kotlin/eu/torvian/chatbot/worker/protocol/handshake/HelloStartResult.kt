package eu.torvian.chatbot.worker.protocol.handshake

/**
 * Result of attempting to start a worker-side hello interaction.
 */
sealed interface HelloStartResult {
    /**
     * Successful start result.
     *
     * @property interactionId Interaction identifier of the launched handshake.
     */
    data class Started(
        val interactionId: String
    ) : HelloStartResult

    /**
     * Failed start result with a logical reason.
     *
     * @property interactionId Generated interaction identifier that failed registration.
     * @property reason Human-readable reason explaining why start failed.
     */
    data class NotStarted(
        val interactionId: String,
        val reason: String
    ) : HelloStartResult
}