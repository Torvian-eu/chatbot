package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.LLMProviderType

/**
 * Action callbacks for the Providers tab.
 */
interface ProvidersTabActions {
    fun onLoadProviders()
    fun onStartAddingNewProvider()
    fun onCancelAddingNewProvider()
    fun onAddNewProvider()
    fun onStartEditingProvider(provider: LLMProvider)
    fun onCancelEditingProvider()
    fun onSaveEditedProviderDetails()
    fun onDeleteProvider(providerId: Long)
    fun onUpdateProviderCredential()

    // Form field updates for new provider
    fun onUpdateNewProviderName(name: String)
    fun onUpdateNewProviderType(type: LLMProviderType)
    fun onUpdateNewProviderBaseUrl(baseUrl: String)
    fun onUpdateNewProviderDescription(description: String)
    fun onUpdateNewProviderCredential(credential: String)

    // Form field updates for editing provider
    fun onUpdateEditingProviderName(name: String)
    fun onUpdateEditingProviderType(type: LLMProviderType)
    fun onUpdateEditingProviderBaseUrl(baseUrl: String)
    fun onUpdateEditingProviderDescription(description: String)
    fun onUpdateEditingProviderNewCredentialInput(credential: String)
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
