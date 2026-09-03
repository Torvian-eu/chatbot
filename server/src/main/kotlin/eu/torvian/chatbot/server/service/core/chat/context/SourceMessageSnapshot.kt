package eu.torvian.chatbot.server.service.core.chat.context

import kotlin.time.Instant

/**
 * Identity snapshot of one original `ChatMessage` represented in a conversation context.
 *
 * The pair (id, updatedAt) is the unit of chunk coverage: compaction eligibility requires every
 * covered ID to belong to the current parent chain and every observed timestamp to still match.
 * Session identity is carried by the enclosing turn and is not repeated here.
 *
 * @property id Database ID of the original `ChatMessage`.
 * @property updatedAt Last-updated timestamp of the original message at context-build time.
 */
data class SourceMessageSnapshot(
    val id: Long,
    val updatedAt: Instant
)