package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for creating a service-token challenge for a worker identity.
 *
 * @property workerId Identifier of the worker that needs a new signed challenge.
 * @property certificateFingerprint Certificate fingerprint proving which worker identity is requested.
 */
@Serializable
data class ServiceTokenChallengeRequest(
    val workerId: Long,
    val certificateFingerprint: String
)

