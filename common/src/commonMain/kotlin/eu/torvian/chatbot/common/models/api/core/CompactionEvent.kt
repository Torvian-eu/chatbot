package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.api.ApiError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-to-client events of the manual conversation-compaction socket
 * (`/api/v1/sessions/{sessionId}/compaction`).
 *
 * The hierarchy is deliberately separate from the chat-turn surfaces ([ChatEvent] / [ChatStreamEvent]):
 * the socket serves one auxiliary, non-transcript operation and is answered with exactly one terminal
 * outcome event followed by [StreamCompleted]. Connecting is the request; closing the socket aborts the
 * operation.
 *
 * [eventType] is descriptive only — it is mirrored from the chat-turn vocabulary for log consistency
 * and is never used for routing.
 */
@Serializable
sealed interface CompactionEvent {
    /** Human-readable kind of this event, mirroring the equivalent chat-turn event name. */
    val eventType: String

    /**
     * A chunk was persisted for the requested thread.
     *
     * @property payload The shared compaction notification details.
     */
    @Serializable
    @SerialName("compaction_completed")
    data class Completed(val payload: CompactionCompletedPayload) : CompactionEvent {
        override val eventType: String = "conversation_compacted"
    }

    /**
     * Nothing was persisted: there was nothing to compact, the thread was already fully covered by
     * an eligible retained chunk, or the generated summary did not shrink the content it replaces.
     *
     * @property reason Which no-op case occurred.
     */
    @Serializable
    @SerialName("compaction_skipped")
    data class Skipped(val reason: CompactionSkipReason) : CompactionEvent {
        override val eventType: String = "conversation_compaction_skipped"
    }

    /**
     * The operation failed; no chunk was persisted.
     *
     * @property error The mapped API error, describing either an unusable configuration or a failed
     *            compaction.
     */
    @Serializable
    @SerialName("compaction_error")
    data class ErrorOccurred(val error: ApiError) : CompactionEvent {
        override val eventType: String = "error"
    }

    /**
     * Terminal marker: the operation is over and the socket is closing normally.
     */
    @Serializable
    @SerialName("compaction_finished")
    data object StreamCompleted : CompactionEvent {
        override val eventType: String = "done"
    }
}
