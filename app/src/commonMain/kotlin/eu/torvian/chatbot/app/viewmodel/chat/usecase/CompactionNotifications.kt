package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.common.models.api.core.CompactionCompletedPayload
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason

/**
 * User-facing copy of the conversation-compaction notifications, shared by the automatic (turn) and the
 * user-requested (manual) paths so both report an identical outcome.
 */
internal object CompactionNotifications {

    /** Notification text for a chunk that was persisted, derived from the shared payload. */
    fun successText(payload: CompactionCompletedPayload): String =
        "Conversation compacted: ${payload.coveredMessageIds.size} messages summarized " +
            "(${payload.sourceTokenCount} → ${payload.resultTokenCount} tokens)"

    /** Notification text for a request that persisted nothing, specific to the no-op case. */
    fun skipText(reason: CompactionSkipReason): String = when (reason) {
        CompactionSkipReason.ALREADY_COMPACTED -> "Conversation already compacted"
        CompactionSkipReason.NOTHING_TO_COMPACT -> "Nothing to compact in this conversation"
        CompactionSkipReason.SUMMARY_NOT_SMALLER ->
            "Conversation not compacted: the summary was not smaller than the messages it replaces"
    }

    /** Notification text for a request the user cancelled before it produced an outcome. */
    const val CANCELLED_TEXT: String = "Conversation compaction cancelled"
}
