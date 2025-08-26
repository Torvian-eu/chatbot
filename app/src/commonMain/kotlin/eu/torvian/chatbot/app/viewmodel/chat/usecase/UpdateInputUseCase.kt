package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.viewmodel.chat.state.InteractionState

/**
 * Use case for updating the input content in the chat.
 */
class UpdateInputUseCase(
    private val state: InteractionState
) {
    
    /**
     * Updates the input content with the provided text.
     * 
     * @param newText The new text content for the input field
     */
    fun execute(newText: String) {
        state.setInputContent(newText)
    }
}
