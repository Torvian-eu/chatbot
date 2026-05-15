package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for the public device verification email request.
 *
 * This endpoint is for users blocked by STRICT mode on new devices.
 * It relies on rate-limiting and trust-checks to prevent abuse.
 *
 * @property username The username of the account.
 * @property deviceId The client-side UUID of the device to verify.
 */
@Serializable
data class RequestPublicDeviceVerificationRequest(
    val username: String,
    val deviceId: String
)
