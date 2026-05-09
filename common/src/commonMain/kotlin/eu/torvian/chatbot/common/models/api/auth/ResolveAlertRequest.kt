package eu.torvian.chatbot.common.models.api.auth

import eu.torvian.chatbot.common.security.SecurityAuditStatus
import kotlinx.serialization.Serializable

/**
 * Request body for resolving a single security alert.
 *
 * @property outcome The outcome to apply to the alert (TRUSTED or DISMISSED).
 */
@Serializable
data class ResolveAlertRequest(
    val outcome: SecurityAuditStatus
)
