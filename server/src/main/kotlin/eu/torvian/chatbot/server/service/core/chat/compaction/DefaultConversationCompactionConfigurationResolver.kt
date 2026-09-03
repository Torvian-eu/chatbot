package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.provider.GetProviderError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.error.CredentialError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [ConversationCompactionConfigurationResolver].
 *
 * The runtime fast path used when compaction is required: loads the model/settings/provider rows and
 * validates the concerns that can change after the preference was stored (existence, activity,
 * model/settings pairing, credential availability). It deliberately does **not** re-check READ access
 * or the chat-like/non-streaming settings profile — those are static write-time concerns enforced by
 * the configuration service.
 *
 * @property llmModelService Loads the compaction model.
 * @property modelSettingsService Loads the compaction settings profile.
 * @property llmProviderService Loads the provider owning the compaction model.
 * @property credentialManager Resolves the provider credential when configured.
 */
class DefaultConversationCompactionConfigurationResolver(
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val llmProviderService: LLMProviderService,
    private val credentialManager: CredentialManager,
) : ConversationCompactionConfigurationResolver {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultConversationCompactionConfigurationResolver::class.java)
    }

    override suspend fun resolveAuxiliaryConfig(
        userId: Long,
        preference: ConversationCompactionPreference
    ): Either<ConversationCompactionError, LLMConfig> {
        // Structural requirements are validated before any lookup so a corrupt preference fails with
        // a deterministic configuration error instead of a confusing missing-row error. A null
        // model/settings reference means the referenced rows no longer exist: the runtime path reaches
        // this resolver only when compaction is actually required (the thread-fits path never calls
        // it), so the resolution attempt reports an invalid configuration right here.
        val modelId = preference.modelId
            ?: return invalid("Compaction modelId is not set").left()
        val settingsId = preference.settingsId
            ?: return invalid("Compaction settingsId is not set").left()
        if (modelId <= 0L) return invalid("Compaction modelId must be positive").left()
        if (settingsId <= 0L) return invalid("Compaction settingsId must be positive").left()
        if (preference.instruction.isBlank()) return invalid("Compaction instruction must not be blank").left()
        if (preference.thresholdTokens <= 0L) return invalid("Compaction thresholdTokens must be positive").left()

        return either {
            val model = withError({ error: GetModelError ->
                invalid("Compaction model $modelId not found: $error")
            }) {
                llmModelService.getModelById(modelId).bind()
            }
            ensure(model.active) { invalid("Compaction model ${model.name} is not active") }

            val settings = withError({ error: GetSettingsByIdError ->
                invalid("Compaction settings $settingsId not found: $error")
            }) {
                modelSettingsService.getSettingsById(settingsId).bind()
            }
            ensure(settings.modelId == model.id) {
                invalid(
                    "Compaction settings ${settings.name} belong to model ${settings.modelId}, " +
                        "not to compaction model ${model.id}"
                )
            }

            val provider = withError({ error: GetProviderError ->
                invalid("Compaction provider ${model.providerId} not found: $error")
            }) {
                llmProviderService.getProviderById(model.providerId).bind()
            }

            val apiKey = provider.apiKeyId?.let { alias ->
                withError({ credentialError: CredentialError ->
                    when (credentialError) {
                        is CredentialError.CredentialNotFound -> invalid(
                            "API key not found in secure storage for compaction provider ${provider.id} (alias: $alias)"
                        )

                        is CredentialError.CredentialDecryptionFailed -> invalid(
                            "API key could not be decrypted for compaction provider ${provider.id} (alias: $alias)"
                        )
                    }
                }) {
                    credentialManager.getCredential(alias).bind()
                }
            }

            logger.debug(
                "Resolved compaction configuration for user {}: provider={}, model={}, settings={}",
                userId,
                provider.id,
                model.id,
                settings.id
            )

            // The instruction is deliberately not part of the LLM config: the service appends it as
            // the final user message of the auxiliary request and persists it as chunk provenance.
            // The config's system message carries the preference's optional system prompt (empty when
            // the preference has none), so both roles stay distinct and non-overlapping.
            LLMConfig(
                provider = provider,
                model = model,
                settings = settings,
                apiKey = apiKey,
                tools = null,
                systemMessage = preference.systemMessage.orEmpty()
            )
        }
    }

    /**
     * Builds a uniform [ConversationCompactionError.InvalidConfiguration] failure reason.
     *
     * @param reason Human-readable reason.
     * @return The typed error (callers wrap it in an [Either.Left] when leaving an `either` block).
     */
    private fun invalid(reason: String): ConversationCompactionError =
        ConversationCompactionError.InvalidConfiguration(reason)
}
