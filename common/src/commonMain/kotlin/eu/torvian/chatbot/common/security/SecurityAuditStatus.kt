package eu.torvian.chatbot.common.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Status of a security audit record.
 *
 * Represents the lifecycle state of a security alert from creation to resolution.
 */
@Serializable
enum class SecurityAuditStatus {
    /**
     * The alert is pending user review.
     * The user has not yet taken action on this alert.
     */
    @SerialName("PENDING")
    PENDING,

    /**
     * The user has trusted this device.
     * The device has been added to the trusted devices list.
     */
    @SerialName("TRUSTED")
    TRUSTED,

    /**
     * The user has dismissed this alert.
     * The device was NOT added to the trusted devices list.
     */
    @SerialName("DISMISSED")
    DISMISSED
}
