package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Per-turn state of the automated conversation-compaction policy.
 *
 * Created once per turn from the current global preference, the retained chunks, and the initial
 * identity-bearing source units; preference changes never affect an in-flight tool loop. Owns the
 * rolling context window (one optional labeled summary + additional uncompressed messages) and the
 * content-free identity ledger: the service updates them on every preflight and the orchestrator grows
 * the window via [appendUnit] after each tool step.
 *
 * The state is one of two variants: [Disabled] (no preference, or `enabled = false` — never raises)
 * or [Enabled] (a decoded enabled preference; a `null` model/settings reference raises
 * `InvalidConfiguration` only when compaction becomes necessary).
 */
sealed interface CompactionTurnState {

    /** Owning chat session. */
    val sessionId: Long

    /**
     * Content-bearing uncompressed window units in thread order. These are the only messages whose
     * content is retained in memory; compacted message text lives solely in the summary and the
     * ledger holds only identities.
     */
    var units: MutableList<ConversationContextUnit>

    /**
     * Appends one newly completed source unit (a persisted assistant message plus its reconstructed
     * tool results) to the rolling window after a tool step, so the next preflight covers it.
     *
     * @param source Identity snapshot of the appended source message.
     * @param rawMessages Provider-facing raw messages derived from the appended source.
     */
    fun appendUnit(source: SourceMessageSnapshot, rawMessages: List<RawChatMessage>) {
        units.add(ConversationContextUnit(source, rawMessages))
    }

    /**
     * No global `conversation_compaction` preference exists, or the stored preference has
     * `enabled = false`: automatic compaction is disabled, the original thread is always sent, and no
     * configuration error is ever raised.
     *
     * @property sessionId Owning chat session.
     * @property units Uncompressed window units; with compaction disabled this is the full thread and
     *            it keeps growing across the loop.
     */
    data class Disabled(
        override val sessionId: Long,
        override var units: MutableList<ConversationContextUnit>
    ) : CompactionTurnState

    /**
     * The global preference decoded successfully; automatic compaction is enabled.
     *
     * @property sessionId Owning chat session.
     * @property ownerUserId Owner of the global preference, needed when compaction becomes required.
     * @property preference The decoded preference snapshot for this turn.
     * @property retainedChunks Chunks loaded once at turn start; grows in-memory as new chunks are
     *            persisted during the same turn.
     * @property units Content-bearing uncompressed window units (never compacted content).
     * @property summaryMessage The current labeled summary message, or null before the first
     *            compaction or when no prior eligible chunk seeded the window.
     * @property coveredSnapshots Content-free identity ledger: the ordered `(id, updatedAt)` of every
     *            message compacted so far this turn, seeded from an eligible chunk's coverage at
     *            window init and extended by each compaction. Holds no message content.
     * @property summaryChunkId Persisted chunk id of the current [summaryMessage] (provenance only).
     * @property initialized True once the first-preflight window init has run.
     */
    data class Enabled(
        override val sessionId: Long,
        val ownerUserId: Long,
        val preference: ConversationCompactionPreference,
        val retainedChunks: MutableList<ConversationCompactionChunk>,
        override var units: MutableList<ConversationContextUnit>,
        var summaryMessage: RawChatMessage.User?,
        var coveredSnapshots: MutableList<SourceMessageSnapshot>,
        var summaryChunkId: Long?,
        var initialized: Boolean
    ) : CompactionTurnState

}