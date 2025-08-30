package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.common.models.LLMProvider

/**
 * Action callbacks for the Providers tab.
 */
interface ProvidersTabActions {
    fun onLoadProviders()
    fun onStartAddingNewProvider()
    fun onCancelProviderForm()
    fun onSaveProviderForm()
    fun onStartEditingProvider(provider: LLMProvider)
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
}

/**
 * Action callbacks for the Settings Config tab.
 */
interface SettingsConfigTabActions {
    fun onLoadModels()
}
