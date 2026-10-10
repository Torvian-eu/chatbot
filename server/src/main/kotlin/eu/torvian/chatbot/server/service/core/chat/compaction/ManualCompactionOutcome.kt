package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason

/**
 * Result of a user-requested conversation compaction.
 *
 * Exactly one of the two cases holds: either a new chunk was persisted, or nothing was persisted
 * because the request was a documented no-op with a reason the client can report. A failed operation
 * is not part of this type — it is returned as a [ConversationCompactionError] by the service that
 * produced the outcome.
 */
sealed interface ManualCompactionOutcome {

    /**
     * A new chunk was persisted for the requested thread.
     *
     * @property chunk The persisted chunk, ready to be reported to the client.
     */
    data class Persisted(val chunk: ConversationCompactionChunk) : ManualCompactionOutcome

    /**
     * Nothing was persisted; the request was a documented no-op.
     *
     * @property reason Which no-op case occurred.
     */
    data class Skipped(val reason: CompactionSkipReason) : ManualCompactionOutcome
}
