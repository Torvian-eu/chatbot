package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for updating an LLM provider's API key credential.
 *
 * @property credential The new API key credential to store securely, or null to remove the credential.
 */
@Serializable
data class UpdateProviderCredentialRequest(
    val credential: String?
)
