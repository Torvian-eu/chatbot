package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the `workers` table.
 *
 * @property id Stable worker identifier.
 * @property ownerUserId User that owns this worker registration.
 * @property displayName User-provided worker display name.
 * @property certificatePem PEM-encoded X.509 certificate used for authentication.
 * @property certificateFingerprint SHA-256 fingerprint used for certificate identity mapping.
 * @property allowedScopes Logical scopes the worker is allowed to request in service tokens.
 * @property createdAt Registration timestamp.
 * @property lastSeenAt Last successful authentication timestamp, when available.
 */
data class WorkerEntity(
    val id: Long,
    val ownerUserId: Long,
    val displayName: String,
    val certificatePem: String,
    val certificateFingerprint: String,
    val allowedScopes: List<String>,
    val createdAt: Instant,
    val lastSeenAt: Instant?
)

