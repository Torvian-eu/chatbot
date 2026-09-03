package eu.torvian.chatbot.server.service.core.chat.context

import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * One source unit of a conversation context.
 *
 * A unit groups all provider-facing [eu.torvian.chatbot.server.service.llm.RawChatMessage] values generated from a single original
 * `ChatMessage`. An assistant source expands into its assistant raw message plus every reconstructed
 * tool-result raw message, so replacing or emitting a unit can never split an assistant tool call
 * from its results.
 *
 * @property source Identity snapshot of the originating `ChatMessage`.
 * @property rawMessages Provider-facing raw messages derived from the source, in order.
 */
data class ConversationContextUnit(
    val source: SourceMessageSnapshot,
    val rawMessages: List<RawChatMessage>
)