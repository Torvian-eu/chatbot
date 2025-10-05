package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Consolidated state for all dialog management in the ModelsTab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class ModelsDialogState {
    object None : ModelsDialogState()

    data class AddNewModel(
        val formState: ModelFormState = ModelFormState(mode = FormMode.NEW),
        val providers: List<LLMProvider>
    ) : ModelsDialogState()

    data class EditModel(
        val model: LLMModel,
        val formState: ModelFormState = ModelFormState.fromModel(model),
        val providers: List<LLMProvider>
    ) : ModelsDialogState()

    data class DeleteModel(val model: LLMModel) : ModelsDialogState()
}