package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * One-time challenge table for worker proof-of-possession.
 *
 * Challenges are consumed after successful verification.
 *
 * @property challengeId Stable challenge identifier.
 * @property workerId FK to the worker the challenge belongs to.
 * @property challenge Plain-text challenge message that must be signed.
 * @property expiresAt Challenge expiration timestamp in epoch milliseconds.
 * @property consumedAt Consumption timestamp when verification succeeds.
 * @property createdAt Creation timestamp in epoch milliseconds.
 */
object WorkerAuthChallengesTable : Table("worker_auth_challenges") {
    val challengeId = varchar("challenge_id", 64)
    val workerId = reference("worker_id", WorkersTable, onDelete = ReferenceOption.CASCADE)
    val challenge = text("challenge")
    val expiresAt = long("expires_at")
    val consumedAt = long("consumed_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(challengeId)
}

