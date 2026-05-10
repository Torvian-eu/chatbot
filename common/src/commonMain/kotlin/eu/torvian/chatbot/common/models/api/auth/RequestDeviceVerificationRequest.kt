package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for requesting a device verification email.
 *
 * @property deviceId The client-side UUID of the device to verify.
 */
@Serializable
data class RequestDeviceVerificationRequest(
    val deviceId: String
)
