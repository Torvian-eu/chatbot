package eu.torvian.chatbot.common.models.worker

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Public worker representation returned by worker/auth endpoints.
 *
 * This model is shared by registration and service-token responses so the
 * server does not need separate API-facing worker DTOs.
 *
 * @property id Stable worker identifier.
 * @property ownerUserId User that owns this worker registration.
 * @property displayName User-provided worker display name.
 * @property certificatePem PEM-encoded public certificate used to verify signatures.
 * @property certificateFingerprint SHA-256 fingerprint used for certificate identity mapping.
 * @property allowedScopes Logical scopes the worker is allowed to request in service tokens.
 * @property createdAt Registration timestamp.
 * @property lastSeenAt Last successful authentication timestamp, when available.
 */
@Serializable
data class Worker(
    val id: Long,
    val ownerUserId: Long,
    val displayName: String,
    val certificatePem: String,
    val certificateFingerprint: String,
    val allowedScopes: List<String>,
    val createdAt: Instant,
    val lastSeenAt: Instant? = null
)