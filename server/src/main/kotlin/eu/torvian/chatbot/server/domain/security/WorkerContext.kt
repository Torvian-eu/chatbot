package eu.torvian.chatbot.server.domain.security

import kotlin.time.Instant

/**
 * Authenticated worker principal resolved from a service JWT.
 *
 * @property workerId Authenticated worker identifier.
 * @property ownerUserId Owning user identifier bound in the token.
 * @property scopes Service scopes granted to the worker token.
 * @property tokenIssuedAt Token issuance timestamp.
 * @property tokenExpiresAt Token expiration timestamp.
 */
data class WorkerContext(
    val workerId: Long,
    val ownerUserId: Long,
    val scopes: List<String>,
    val tokenIssuedAt: Instant,
    val tokenExpiresAt: Instant
)

