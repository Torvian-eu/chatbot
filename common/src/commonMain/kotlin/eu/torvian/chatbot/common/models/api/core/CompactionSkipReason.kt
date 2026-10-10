package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Explains why a user-requested conversation compaction persisted nothing.
 *
 * The reason travels on the compaction socket as part of [CompactionEvent.Skipped] so the client can
 * report a specific informational message instead of a generic one. A skip is never a failure: the
 * displayed thread is left exactly as it was.
 */
@Serializable
enum class CompactionSkipReason {
    /**
     * The largest eligible retained chunk already covers every message of the displayed thread, so a
     * new compaction could only re-summarize the same content.
     */
    ALREADY_COMPACTED,

    /**
     * The displayed thread has no message to summarize (the session has no leaf message).
     */
    NOTHING_TO_COMPACT,

    /**
     * The generated summary was not smaller than the messages it replaces, so persisting it would
     * replace faithful raw content with a lossy equivalent of the same or greater size.
     */
    SUMMARY_NOT_SMALLER
}
