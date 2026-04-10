package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Persistence entity for a one-time worker challenge.
 *
 * @property challengeId Stable challenge identifier.
 * @property workerId Worker this challenge belongs to.
 * @property challenge Challenge message expected to be signed.
 * @property expiresAt Challenge expiration timestamp.
 * @property consumedAt Timestamp when challenge was consumed.
 * @property createdAt Challenge creation timestamp.
 */
data class WorkerAuthChallengeEntity(
    val challengeId: String,
    val workerId: Long,
    val challenge: String,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val createdAt: Instant
)

