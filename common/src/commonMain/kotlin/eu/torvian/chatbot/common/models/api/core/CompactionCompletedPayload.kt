package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Provider-neutral wire payload for the `conversation_compacted` event on both chat event surfaces
 * (the SSE non-streaming [ChatEvent] stream and the streaming [ChatStreamEvent] stream).
 *
 * The event is informational: it reports that the server persisted a compaction chunk **before**
 * the primary response that uses it was generated. It identifies the persisted chunk, the ordered
 * original message IDs the chunk covers, the before/after token counts of the compacted primary
 * input, the compaction model/settings/provider provenance (ids plus immutable name snapshots that
 * survive row deletion), a bounded summary preview, and the chunk creation timestamp. It never
 * exposes a new transcript message and requires no client-side message replacement; it must not be
 * stored into the visible conversation.
 *
 * **Wire contract:**
 * - `chunkId`: primary key of the persisted `conversation_compaction_chunk` row.
 * - `sessionId`: chat session the chunk belongs to.
 * - `coveredMessageIds`: the ordered original message IDs covered by the chunk (chronological
 *   coverage order); may be large, so clients should not render the list verbatim.
 * - `modelId`/`settingsId`/`providerId`: nullable ids of the compaction model/settings/provider at
 *   creation time (null when the row was deleted afterwards).
 * - `modelName`/`settingsName`/`providerName`: immutable name snapshots of the same provenance.
 * - `sourceTokenCount`: estimated full primary input tokens before this compaction.
 * - `resultTokenCount`: estimated one-summary primary input tokens after this compaction.
 * - `summaryPreview`: bounded preview of the summary (never the full summary; see
 *   [MAX_SUMMARY_PREVIEW_CHARS] and [SUMMARY_PREVIEW_TRUNCATION_MARKER]). The preview never carries
 *   the compaction instruction, reasoning content, or credentials.
 * - `createdAt`: epoch-millisecond creation time of the chunk.
 *
 * The payload deliberately contains no provider/lazy/network internals: it is a plain serializable
 * value shared by both surfaces so clients can handle the event uniformly.
 *
 * @property chunkId Persisted compaction-chunk id that produced this event.
 * @property sessionId Chat session owning the chunk.
 * @property coveredMessageIds Ordered original message ids covered by the chunk.
 * @property modelId Compaction model id at creation, or null after deletion.
 * @property settingsId Compaction settings id at creation, or null after deletion.
 * @property providerId Compaction provider id at creation, or null after deletion.
 * @property modelName Immutable compaction model-name snapshot.
 * @property settingsName Immutable compaction settings-name snapshot.
 * @property providerName Immutable compaction provider-name snapshot.
 * @property sourceTokenCount Estimated full primary input before compaction.
 * @property resultTokenCount Estimated one-summary primary input after compaction.
 * @property summaryPreview Bounded preview of the generated summary.
 * @property createdAt Epoch-millisecond creation time of the chunk.
 */
@Serializable
data class CompactionCompletedPayload(
    val chunkId: Long,
    val sessionId: Long,
    val coveredMessageIds: List<Long>,
    val modelId: Long?,
    val settingsId: Long?,
    val providerId: Long?,
    val modelName: String,
    val settingsName: String,
    val providerName: String,
    val sourceTokenCount: Long,
    val resultTokenCount: Long,
    val summaryPreview: String,
    val createdAt: Long
) {
    companion object {
        /**
         * Upper bound, in characters, of [summaryPreview] on the wire. The server truncates the
         * summary to this many characters so events never carry the full summary content.
         */
        const val MAX_SUMMARY_PREVIEW_CHARS: Int = 500

        /**
         * Marker appended by the server when the summary was truncated to
         * [MAX_SUMMARY_PREVIEW_CHARS] characters. The marker is part of the preview and counts
         * toward the character budget.
         */
        const val SUMMARY_PREVIEW_TRUNCATION_MARKER: String = "…"
    }
}