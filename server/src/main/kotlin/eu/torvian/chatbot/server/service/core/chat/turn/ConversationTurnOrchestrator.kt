package eu.torvian.chatbot.server.service.core.chat.turn

import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates a single conversation turn after request validation has already succeeded.
 */
interface ConversationTurnOrchestrator {
    /**
     * Processes a non-streaming conversation turn and emits internal runtime events.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @return Flow of internal lifecycle events for the turn.
     */
    fun processNonStreamingTurn(request: ConversationTurnRequest): Flow<ConversationTurnEvent>

    /**
     * Processes a streaming conversation turn and emits internal runtime events.
     *
     * @param request Immutable input bundle for the turn being processed.
     * @return Flow of internal lifecycle events for the turn.
     */
    fun processStreamingTurn(request: ConversationTurnRequest): Flow<ConversationTurnEvent>
}

