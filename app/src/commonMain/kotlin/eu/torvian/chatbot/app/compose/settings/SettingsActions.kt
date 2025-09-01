package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.LLMModel

/**
 * Action callbacks for the Providers tab.
 */
interface ProvidersTabActions {
    fun onLoadProviders()
    fun onSelectProvider(provider: LLMProvider?)
    fun onStartAddingNewProvider()
    fun onCancelDialog()
    fun onSaveProvider()
    fun onStartEditingProvider(provider: LLMProvider)
    fun onStartDeletingProvider(provider: LLMProvider)
    fun onDeleteProvider(providerId: Long)
    fun onUpdateProviderCredential()

    // Unified form field updates
    fun onUpdateProviderForm(update: (ProviderFormState) -> ProviderFormState)
}

/**
 * Action callbacks for the Models tab.
 */
interface ModelsTabActions {
    fun onLoadModelsAndProviders()
    fun onStartAddingNewModel()
    fun onSaveModel()
    fun onStartEditingModel(model: LLMModel)
    fun onStartDeletingModel(model: LLMModel)
    fun onDeleteModel(modelId: Long)
    fun onSelectModel(model: LLMModel?)

    // Unified form field updates
    fun onUpdateModelForm(update: (ModelFormState) -> ModelFormState)

    fun onCancelDialog()
}

/**
 * Action callbacks for the Settings Config tab.
 */
interface SettingsConfigTabActions {
    fun onLoadModels()
}
