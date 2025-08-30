package eu.torvian.chatbot.app.domain.contracts

/**
 * Enum representing the mode of form operations - whether creating new entities or editing existing ones.
 * Used across different form types including model configuration and settings forms.
 */
enum class FormMode {
    NEW,
    EDIT
}