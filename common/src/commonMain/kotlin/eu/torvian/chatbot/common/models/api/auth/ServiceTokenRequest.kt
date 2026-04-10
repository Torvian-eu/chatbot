package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for exchanging a signed worker challenge for a service token.
 *
 * @property workerId Identifier of the worker being authenticated.
 * @property challengeId Identifier of the issued challenge.
 * @property signatureBase64 Base64-encoded signature over the challenge payload.
 */
@Serializable
data class ServiceTokenRequest(
    val workerId: Long,
    val challengeId: String,
    val signatureBase64: String
)

