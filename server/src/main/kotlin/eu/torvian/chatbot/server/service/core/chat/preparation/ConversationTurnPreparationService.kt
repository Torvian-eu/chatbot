package eu.torvian.chatbot.server.service.core.chat.preparation

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError

/**
 * Validates an incoming chat-turn request and resolves the runtime inputs needed to execute it.
 */
interface ConversationTurnPreparationService {
    /**
     * Validates the request shape and assembles the resolved session plus LLM runtime configuration.
     *
     * @param sessionId Session that should receive the new turn.
     * @param content Optional user content. When null, the request continues from [parentMessageId].
     * @param parentMessageId Optional parent message to continue from.
     * @param isStreaming Requested assistant delivery mode, used to validate settings compatibility.
     * @return Either the original validation error surface or the prepared runtime inputs.
     */
    suspend fun prepareNewMessageTurn(
        sessionId: Long,
        content: String?,
        parentMessageId: Long?,
        isStreaming: Boolean
    ): Either<ValidateNewMessageError, PreparedConversationTurn>
}

/**
 * Carries the fully prepared runtime inputs for a validated conversation turn.
 *
 * @property session Loaded chat session that will receive the turn.
 * @property llmConfig Resolved LLM runtime configuration assembled for the turn.
 */
data class PreparedConversationTurn(
    val session: ChatSession,
    val llmConfig: LLMConfig
)