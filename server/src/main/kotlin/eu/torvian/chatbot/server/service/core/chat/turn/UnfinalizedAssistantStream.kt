package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.server.service.llm.LLMCompletionError

/**
 * Describes a streaming ending that produced no terminal chunk, so the assistant step can persist the
 * matching terminal state exactly once.
 *
 * @property partialContent Content the stream had accumulated when it stopped. It may be empty (a stop
 *           before any text arrived); it is always persisted together with the state so the recorded
 *           reason and the visible content can never disagree.
 */
internal sealed interface UnfinalizedAssistantStream {
    val partialContent: String

    /**
     * The user stopped the turn through the stop control before the provider signalled completion.
     */
    data class CancelledByUser(override val partialContent: String) : UnfinalizedAssistantStream

    /**
     * The provider reported a failure that no terminal chunk followed.
     *
     * @property error Failure reported by the streaming client, mapped to the persisted code and reason.
     */
    data class Failed(
        override val partialContent: String,
        val error: LLMCompletionError
    ) : UnfinalizedAssistantStream

    /**
     * The provider stream ended without any terminal, error or cancellation signal, so the answer may be
     * cut off mid-generation. Reachable for dialects that do not fabricate a completion chunk when the raw
     * stream ends.
     */
    data class StreamEndedUnexpectedly(override val partialContent: String) : UnfinalizedAssistantStream

    /**
     * An unexpected non-cancellation failure occurred while the message was still unfinished.
     */
    data class UnexpectedFailure(override val partialContent: String) : UnfinalizedAssistantStream
}
