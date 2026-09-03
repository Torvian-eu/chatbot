package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Outcome of the pre-primary-call compaction policy for one LLM call.
 *
 * The preflight returns exactly one of three primary contexts: the original flattened thread
 * (first preflight when the raw input fits, or compaction disabled), the rolling hybrid window
 * `[summary] + additional uncompressed messages` (the steady state), or the summary message alone
 * (immediately after a compaction). For an enabled turn, every returned context has just been
 * verified with the authoritative counter to fit the threshold.
 *
 * @property primaryMessages The verified window sent to the primary model.
 * @property persistedChunkIfAny The chunk persisted by this preflight, or `null` when no compaction
 *            ran. The service already updated its own in-memory state; the caller only observes it.
 */
data class PrimaryContextPreflight(
    val primaryMessages: List<RawChatMessage>,
    val persistedChunkIfAny: ConversationCompactionChunk?
)