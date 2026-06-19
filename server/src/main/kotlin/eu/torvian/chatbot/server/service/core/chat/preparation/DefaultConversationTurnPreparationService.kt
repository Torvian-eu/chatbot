package eu.torvian.chatbot.server.service.core.chat.preparation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.hasCapability
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.provider.GetProviderError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError

/**
 * Default implementation that preserves the existing request-validation and runtime-preparation flow
 * before a conversation turn is orchestrated.
 *
 * @property messageDao DAO used to verify the optional parent message.
 * @property sessionDao DAO used to load the target chat session.
 * @property toolService Service used to resolve enabled tools for the session.
 * @property llmModelService Service used to load the selected model.
 * @property modelSettingsService Service used to load the selected settings profile.
 * @property llmProviderService Service used to load the provider that owns the selected model.
 * @property credentialManager Service used to resolve provider credentials when required.
 * @property transactionScope Transaction wrapper that keeps the validation lookup sequence consistent.
 */
class DefaultConversationTurnPreparationService(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val toolService: ToolService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val llmProviderService: LLMProviderService,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope,
) : ConversationTurnPreparationService {
    override suspend fun prepareNewMessageTurn(
        sessionId: Long,
        content: String?,
        parentMessageId: Long?,
        isStreaming: Boolean
    ): Either<ValidateNewMessageError, PreparedConversationTurn> = transactionScope.transaction {
        either {
            ensure(content != null || parentMessageId != null) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Branch & Continue mode requires parentMessageId when content is null"
                )
            }

            val session = withError({ daoError: SessionError.SessionNotFound ->
                ValidateNewMessageError.SessionNotFound(daoError.id)
            }) {
                sessionDao.getSessionById(sessionId).bind()
            }

            if (parentMessageId != null) {
                withError({ _: MessageError.MessageNotFound ->
                    ValidateNewMessageError.ParentNotInSession(sessionId, parentMessageId)
                }) {
                    messageDao.getMessageById(parentMessageId).bind()
                }
            }

            val modelId = session.currentModelId
                ?: raise(ValidateNewMessageError.ModelConfigurationError("No model selected for session $sessionId"))
            val settingsId = session.currentSettingsId
                ?: raise(ValidateNewMessageError.ModelConfigurationError("No settings selected for session $sessionId"))

            val model = withError({ _: GetModelError ->
                throw IllegalStateException("Model with ID $modelId not found after validation")
            }) {
                llmModelService.getModelById(modelId).bind()
            }

            val settings = withError({ _: GetSettingsByIdError ->
                throw IllegalStateException("Settings with ID $settingsId not found after validation")
            }) {
                modelSettingsService.getSettingsById(settingsId).bind()
            }

            ensure(model.type == LLMModelType.CHAT) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Model type ${model.type} is not supported for chat sessions"
                )
            }
            ensure(settings is ChatModelSettings) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Settings type ${settings::class.simpleName} is not compatible with model type ${model.type}"
                )
            }
            ensure(settings.stream == isStreaming) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Settings stream mode ${settings.stream} does not match requested stream mode $isStreaming"
                )
            }

            val provider = withError({ _: GetProviderError ->
                throw IllegalStateException("Provider not found for model ID $modelId (provider ID: ${model.providerId})")
            }) {
                llmProviderService.getProviderById(model.providerId).bind()
            }

            val apiKey = provider.apiKeyId?.let { keyId ->
                withError({ credentialError: CredentialError ->
                    when (credentialError) {
                        is CredentialError.CredentialNotFound -> {
                            throw IllegalStateException(
                                "API key not found in secure storage for provider ID ${provider.id} (key alias: $keyId)"
                            )
                        }

                        is CredentialError.CredentialDecryptionFailed -> {
                            throw IllegalStateException(
                                "API key could not be decrypted for provider ID ${provider.id} (key alias: $keyId)"
                            )
                        }
                    }
                }) {
                    credentialManager.getCredential(keyId).bind()
                }
            }

            // Preserve the existing null-vs-empty distinction because downstream tool handling relies on it.
            val tools = if (model.hasCapability(LLMModelCapabilities.TOOL_CALLING)) {
                toolService.getEnabledToolsForSession(sessionId)
            } else {
                null
            }

            PreparedConversationTurn(
                session = session,
                llmConfig = LLMConfig(provider, model, settings, apiKey, tools)
            )
        }
    }
}