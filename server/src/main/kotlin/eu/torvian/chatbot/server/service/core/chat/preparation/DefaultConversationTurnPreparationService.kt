package eu.torvian.chatbot.server.service.core.chat.preparation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.common.models.llm.hasCapability
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.agent.SystemPromptComposer
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
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
 * Model, settings, tools and the composed system prompt are resolved from the session's selected agent
 * role (a session no longer stores its own model/settings). A session without a role, or a role whose
 * referenced model/settings were deleted (`ON DELETE SET NULL`), raises a [ValidateNewMessageError.ModelConfigurationError].
 *
 * @property messageDao DAO used to verify the optional parent message.
 * @property sessionDao DAO used to load the target chat session.
 * @property toolService Service used to load the role's tool definitions by ID.
 * @property llmModelService Service used to load the selected model.
 * @property modelSettingsService Service used to load the selected settings profile.
 * @property llmProviderService Service used to load the provider that owns the selected model.
 * @property credentialManager Service used to resolve provider credentials when required.
 * @property agentRoleService Service used to load the session's selected agent role.
 * @property systemPromptComposer Composer that builds the system prompt from the role's instructions.
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
    private val agentRoleService: AgentRoleService,
    private val systemPromptComposer: SystemPromptComposer,
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

            // A session is configured exclusively through its agent role: without one there is no
            // model/settings to talk to, so the turn cannot be prepared.
            val agentRoleId = session.agentRoleId
                ?: raise(
                    ValidateNewMessageError.ModelConfigurationError(
                        "No agent role selected for session $sessionId"
                    )
                )

            val role = withError({ _: AgentRoleError.NotFound ->
                ValidateNewMessageError.ModelConfigurationError(
                    "Agent role $agentRoleId selected for session $sessionId no longer exists"
                )
            }) {
                agentRoleService.getAgentRoleById(agentRoleId).bind()
            }

            // model_id/model_settings_id are nullable because deleting the referenced model/settings
            // nulls them (ON DELETE SET NULL); re-check at turn time so a broken role fails loudly.
            val modelId = role.modelId
                ?: raise(
                    ValidateNewMessageError.ModelConfigurationError(
                        "Agent role $agentRoleId for session $sessionId references a deleted model"
                    )
                )
            val settingsId = role.modelSettingsId
                ?: raise(
                    ValidateNewMessageError.ModelConfigurationError(
                        "Agent role $agentRoleId for session $sessionId references deleted settings"
                    )
                )

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

            ensure(isChatLikeSettings(settings)) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Settings type ${settings::class.simpleName} is not compatible with the selected chat model"
                )
            }
            ensure(chatStreamFlag(settings) == isStreaming) {
                ValidateNewMessageError.ModelConfigurationError(
                    "Settings stream mode does not match requested stream mode $isStreaming"
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

            // Tools come from the role's tool-id set (stored in the `agent_role_tools` join table).
            // Load them with a single batch query rather than one query per id; `ON DELETE CASCADE`
            // guarantees every id resolves, so the mapNotNull below is unreachable defense-in-depth.
            // Preserve the null-vs-empty distinction because downstream tool handling relies on it.
            val tools = if (model.hasCapability(LLMModelCapabilities.TOOL_CALLING)) {
                val toolsById = toolService.getToolsByIds(role.tools)
                role.tools.mapNotNull { toolsById[it] }
            } else {
                null
            }

            // The composed system prompt is the single source of truth for the system message. When the
            // role has no instructions (or all are blank) this is empty, and strategies omit the system
            // message entirely — the settings' own system text is never injected.
            val systemMessage = systemPromptComposer.compose(role.instructions)

            PreparedConversationTurn(
                session = session,
                llmConfig = LLMConfig(provider, model, settings, apiKey, tools, systemMessage)
            )
        }
    }

    /**
     * Whether the given [ModelSettings] is compatible with a CHAT or RESPONSES chat-session model.
     * Both [ChatModelSettings] and [ResponsesModelSettings] describe conversational generation and
     * carry a `stream` flag, so either may be attached to a chat session.
     *
     * @receiver The settings profile to inspect.
     * @return `true` if the settings describe a chat-capable model type.
     */
    private fun isChatLikeSettings(settings: ModelSettings): Boolean = when (settings) {
        is ChatModelSettings -> true
        is ResponsesModelSettings -> true
        else -> false
    }

    /**
     * Extracts the streaming flag from chat-capable settings. Returns `null` for settings that do not
     * describe a chat-capable model (i.e. types other than [ChatModelSettings] and [ResponsesModelSettings]).
     *
     * @receiver The settings profile to inspect.
     * @return The `stream` flag when the settings are chat-capable, otherwise `null`.
     */
    private fun chatStreamFlag(settings: ModelSettings): Boolean? = when (settings) {
        is ChatModelSettings -> settings.stream
        is ResponsesModelSettings -> settings.stream
        else -> null
    }
}
