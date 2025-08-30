package eu.torvian.chatbot.app.domain.contracts

/**
 * Data class representing the state of the "Add New Settings Profile" form.
 */
data class NewSettingsFormState(
    val name: String = "",
    val systemMessage: String = "",
    val temperature: String = "", // String for UI input
    val maxTokens: String = "",   // String for UI input
    val topP: String = "",        // String for UI input
    val topK: String = "",        // String for UI input
    val stopSequences: String = "", // Comma-separated string for UI input
    val customParamsJson: String = "",
    val errorMessage: String? = null // For inline validation/API errors
)

/**
 * Data class representing the state of the "Edit Settings Profile" form.
 */
data class EditSettingsFormState(
    val name: String = "",
    val systemMessage: String = "",
    val temperature: String = "", // String for UI input
    val maxTokens: String = "",   // String for UI input
    val topP: String = "",        // String for UI input
    val topK: String = "",        // String for UI input
    val stopSequences: String = "", // Comma-separated string for UI input
    val customParamsJson: String = "",
    val errorMessage: String? = null // For inline validation/API errors
)
