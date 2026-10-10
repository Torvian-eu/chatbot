package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.ManualCompactionOutcome
import eu.torvian.chatbot.server.service.core.chat.compaction.toCompactionCompletedPayload
import eu.torvian.chatbot.server.service.core.error.message.toApiError

/**
 * Converts the outcome of a user-requested compaction into its wire event.
 *
 * The completed arm reuses the shared bounded compaction payload, so the client renders a manual
 * success with the same notification it uses for an automatic one.
 *
 * @receiver The outcome produced by the manual compaction service.
 * @return The terminal event describing what was persisted, or that nothing was.
 */
fun ManualCompactionOutcome.toCompactionEvent(): CompactionEvent = when (this) {
    is ManualCompactionOutcome.Persisted -> CompactionEvent.Completed(chunk.toCompactionCompletedPayload())
    is ManualCompactionOutcome.Skipped -> CompactionEvent.Skipped(reason)
}

/**
 * Converts a failed user-requested compaction into its wire event.
 *
 * The error keeps the existing compaction error taxonomy, so the client reports a manual failure with
 * the same mapping it uses for an automatic one.
 *
 * @receiver The compaction failure to expose.
 * @return The terminal error event carrying the mapped API error.
 */
fun ConversationCompactionError.toCompactionEvent(): CompactionEvent =
    CompactionEvent.ErrorOccurred(toApiError())
