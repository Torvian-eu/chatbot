package eu.torvian.chatbot.worker.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Persisted worker service token used as local auth cache.
 *
 * @property accessToken Bearer token presented to authenticated worker endpoints.
 * @property expiresAt Absolute expiration timestamp used for refresh decisions.
 */
@Serializable
data class StoredServiceToken(
    val accessToken: String,
    val expiresAt: Instant
)

