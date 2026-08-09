package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.LLMModel
import kotlinx.serialization.json.JsonObject

/**
 * Data class representing the state of model configuration forms.
 * Used for both adding new models and editing existing ones, distinguished by the mode field.
 */
data class ModelFormState(
    val mode: FormMode = FormMode.NEW,
    val name: String = "", // e.g., "gpt-3.5-turbo"
    val providerId: Long? = null,
    val active: Boolean = true,
    val displayName: String = "", // Optional, display name for UI
    val capabilities: JsonObject = JsonObject(emptyMap()), // Raw JsonObject for flexibility
    val errorMessage: String? = null // For inline validation/API errors
) {
    /**
     * Creates a copy of this form state with the error message cleared.
     */
    fun withError(errorMessage: String?): ModelFormState = copy(errorMessage = errorMessage)

    companion object {
        /**
         * Creates a ModelFormState from an existing LLMModel for editing.
         */
        fun fromModel(model: LLMModel): ModelFormState = ModelFormState(
            mode = FormMode.EDIT,
            name = model.name,
            providerId = model.providerId,
            active = model.active,
            displayName = model.displayName ?: "",
            capabilities = model.capabilities ?: JsonObject(emptyMap())
        )
    }
}
