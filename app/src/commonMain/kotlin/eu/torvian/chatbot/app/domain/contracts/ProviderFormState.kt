package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.LLMProviderType

/**
 * Data class representing the state of provider configuration forms.
 * Used for both adding new providers and editing existing ones, distinguished by the mode field.
 */
data class ProviderFormState(
    val mode: FormMode = FormMode.NEW,
    val name: String = "",
    val description: String = "",
    val baseUrl: String = "",
    val type: LLMProviderType = LLMProviderType.OPENAI, // Default to a common type
    val credential: String = "", // For new providers: API key input. For edit: new credential input
    val errorMessage: String? = null // For inline validation/API errors
) {
    /**
     * Creates a copy of this form state with the error message set.
     */
    fun withError(errorMessage: String?): ProviderFormState = copy(errorMessage = errorMessage)
}
