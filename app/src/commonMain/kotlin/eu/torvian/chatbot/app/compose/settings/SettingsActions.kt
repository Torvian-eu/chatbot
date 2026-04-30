package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.domain.contracts.ModelSettingsFormState
import eu.torvian.chatbot.app.domain.contracts.WorkersFormState
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * Action callbacks for the Providers tab.
 */
interface ProvidersTabActions {
    fun onLoadProviders()
    fun onSelectProvider(providerDetails: LLMProviderDetails?)
    fun onStartAddingNewProvider()
    fun onCancelDialog()
    fun onSaveProvider()
    fun onStartEditingProvider(provider: LLMProvider)
    fun onStartDeletingProvider(provider: LLMProvider)
    fun onDeleteProvider(providerId: Long)
    fun onUpdateProviderCredential()
    fun onTestProviderConnectionInDialog()
    fun onListProviderModels(providerId: Long)
    fun onUpdateProviderForm(update: (ProviderFormState) -> ProviderFormState)

    // Access management actions
    fun onMakeProviderPublic(providerDetails: LLMProviderDetails)
    fun onMakeProviderPrivate(providerDetails: LLMProviderDetails)
    fun onOpenManageAccessDialog(providerDetails: LLMProviderDetails)
    fun onOpenGrantAccessDialog()
    fun onCloseGrantAccessDialog()
    fun onUpdateGrantAccessForm(form: GrantAccessFormState)
    fun onGrantProviderAccess(providerId: Long, groupId: Long, accessMode: String)
    fun onRevokeProviderAccess(providerId: Long, groupId: Long, accessMode: String)
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
    fun onSelectModel(modelDetails: LLMModelDetails?)
    fun onUpdateModelForm(update: (ModelFormState) -> ModelFormState)
    fun onCancelDialog()

    // Access management actions
    fun onMakeModelPublic(modelDetails: LLMModelDetails)
    fun onMakeModelPrivate(modelDetails: LLMModelDetails)
    fun onOpenManageAccessDialog(modelDetails: LLMModelDetails)
    fun onOpenGrantAccessDialog()
    fun onCloseGrantAccessDialog()
    fun onUpdateGrantAccessForm(form: GrantAccessFormState)
    fun onGrantModelAccess(modelId: Long, groupId: Long, accessMode: String)
    fun onRevokeModelAccess(modelId: Long, groupId: Long, accessMode: String)
}

/**
 * Action callbacks for the Settings Config tab.
 */
interface ModelSettingsConfigTabActions {
    fun onLoadModelsAndSettings()
    fun onSelectModel(model: LLMModel?)
    fun onSelectSettings(settingsDetails: ModelSettingsDetails?)
    fun onStartAddingNewSettings()
    fun onStartEditingSettings(settings: ModelSettings)
    fun onStartDeletingSettings(settings: ModelSettings)
    fun onUpdateSettingsForm(update: (ModelSettingsFormState) -> ModelSettingsFormState)
    fun onSaveSettings()
    fun onDeleteSettings(settingsId: Long)
    fun onCancelDialog()

    // Access management actions
    fun onMakeSettingsPublic(settingsDetails: ModelSettingsDetails)
    fun onMakeSettingsPrivate(settingsDetails: ModelSettingsDetails)
    fun onOpenManageAccessDialog(settingsDetails: ModelSettingsDetails)
    fun onOpenGrantAccessDialog()
    fun onCloseGrantAccessDialog()
    fun onUpdateGrantAccessForm(form: GrantAccessFormState)
    fun onGrantSettingsAccess(settingsId: Long, groupId: Long, accessMode: String)
    fun onRevokeSettingsAccess(settingsId: Long, groupId: Long, accessMode: String)
}

/**
 * Action callbacks for the Workers tab.
 */
interface WorkersTabActions {
    fun onLoadWorkers()
    fun onStartEditingWorker(worker: WorkerDto)
    fun onStartDeletingWorker(worker: WorkerDto)
    fun onUpdateWorkerForm(update: (WorkersFormState) -> WorkersFormState)
    fun onSaveWorker()
    fun onDeleteWorker(workerId: Long)
    fun onCancelDialog()
}
