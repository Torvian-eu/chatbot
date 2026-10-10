package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Per-turn state of the automated conversation-compaction policy.
 *
 * Created once per turn from the turn's resolved compaction configuration, the retained chunks, and
 * the initial identity-bearing source units; later configuration changes never affect an in-flight
 * tool loop. Owns the rolling context window (one optional labeled summary + additional uncompressed
 * messages) and the content-free identity ledger: the service updates them on every preflight and the
 * orchestrator grows the window via [appendUnit] after each tool step.
 *
 * The state is one of two variants: [Inactive] (no usable auxiliary configuration — never raises) or
 * [Active] (a usable configuration, which either compacts automatically when enabled or only injects an
 * existing eligible summary when disabled).
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
     * No usable auxiliary configuration exists for the turn. The original thread is always sent, no
     * counting happens, no summary is injected and no configuration error is ever raised.
     *
     * @property sessionId Owning chat session.
     * @property units Uncompressed window units; without a usable configuration this is the full thread
     *            and it keeps growing across the loop.
     */
    data class Inactive(
        override val sessionId: Long,
        override var units: MutableList<ConversationContextUnit>
    ) : CompactionTurnState

    /**
     * A usable auxiliary configuration exists for the turn.
     *
     * Threshold-triggered compaction runs only when [automaticCompactionEnabled] is true: when it is
     * false the window is still seeded from an eligible retained chunk and sent as-is, without counting
     * or an auxiliary call.
     *
     * @property sessionId Owning chat session.
     * @property ownerUserId Owner of the global preference, needed when compaction becomes required.
     * @property settings The turn's effective settings, carrying the effective threshold, the auxiliary
     *            model, settings, instruction, system message and summary label.
     * @property automaticCompactionEnabled Whether automatic (threshold-triggered) compaction is
     *            enabled for the whole turn. It gates only that path: summary injection
     *            and a forced compaction stay available while it is false.
     * @property retainedChunks Chunks loaded once when the turn starts; grows in-memory as new chunks
     *            are persisted during the same turn.
     * @property units Content-bearing uncompressed window units (never compacted content).
     * @property summaryMessage The current labeled summary message, or null before the first
     *            compaction or when no prior eligible chunk seeded the window.
     * @property coveredSnapshots Content-free identity ledger: the ordered `(id, updatedAt)` of every
     *            message compacted so far this turn, seeded from an eligible chunk's coverage at
     *            window init and extended by each compaction. Holds no message content.
     */
    data class Active(
        override val sessionId: Long,
        val ownerUserId: Long,
        val settings: EffectiveCompactionSettings,
        val automaticCompactionEnabled: Boolean,
        val retainedChunks: MutableList<ConversationCompactionChunk>,
        override var units: MutableList<ConversationContextUnit>,
        var summaryMessage: RawChatMessage.User?,
        var coveredSnapshots: MutableList<SourceMessageSnapshot>
    ) : CompactionTurnState

}
