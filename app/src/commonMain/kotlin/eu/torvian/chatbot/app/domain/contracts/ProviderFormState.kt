package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.LLMProviderType

/**
 * Data class representing the state of the "Add New Provider" form.
 */
data class NewProviderFormState(
    val name: String = "",
    val description: String = "",
    val baseUrl: String = "",
    val type: LLMProviderType = LLMProviderType.OPENAI, // Default to a common type
    val credential: String = "", // Raw API key input
    val errorMessage: String? = null // For inline validation/API errors
)

/**
 * Data class representing the state of the "Edit Provider" form.
 */
data class EditProviderFormState(
    val name: String = "",
    val description: String = "",
    val baseUrl: String = "",
    val type: LLMProviderType = LLMProviderType.OPENAI,
    val newCredentialInput: String = "", // For updating the API key, not showing existing
    val errorMessage: String? = null // For inline validation/API errors
)
