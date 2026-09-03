package eu.torvian.chatbot.server.service.core.chat.context

import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Ordered, identity-bearing conversation context for one parent-linked thread.
 *
 * This is the uncompressed source of truth retained across a tool loop. The primary provider call
 * receives a flattened view ([flatten]) of either the full thread or a single synthetic summary unit;
 * the full context itself is never replaced by compaction so later iterations keep covering every
 * persisted message.
 *
 * @property units Source units in chronological thread order.
 */
data class ConversationContext(val units: List<ConversationContextUnit>) {

    /**
     * Flattens all units into the ordered provider-facing raw message list.
     *
     * @return Raw messages in thread order.
     */
    fun flatten(): List<RawChatMessage> = units.flatMap { it.rawMessages }

    /**
     * Appends one newly completed source unit (e.g. a persisted assistant message plus tool results).
     *
     * @param source Identity snapshot of the appended source message.
     * @param rawMessages Provider-facing raw messages derived from the appended source.
     * @return A new context with the unit appended.
     */
    fun appendUnit(
        source: SourceMessageSnapshot,
        rawMessages: List<RawChatMessage>
    ): ConversationContext = ConversationContext(units + ConversationContextUnit(source, rawMessages))

    companion object {
        /** Empty context used before any message exists. */
        val EMPTY = ConversationContext(emptyList())
    }
}
