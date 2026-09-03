package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [ConversationCompactionConfigurationService].
 *
 * The PUT path decodes the preference, applies structural validation, and then stores the canonical
 * JSON encoding of the preference in the GLOBAL scope so later runtime decoding is deterministic.
 * Before storage it additionally checks the **non-runtime** concerns only — whether the configuration
 * is correct and whether access is allowed: READ access to the referenced model and settings, that
 * the referenced settings exist and belong to the referenced model (settings rows reference an
 * existing model, so the model itself needs no dedicated check here), and that the settings profile
 * is chat-like and non-streaming. Runtime concerns — model activity, provider existence/access,
 * registered strategy, credential resolvability — are validated only when compaction actually runs,
 * by the fast runtime resolver.
 * A preference without a model/settings reference (null ids, e.g. after a server-side row deletion)
 * cannot be checked and is stored as-is; it raises an invalid-configuration error at runtime only
 * when compaction becomes necessary, while the thread-fits path passes through.
 *
 * @property json Shared JSON codec used to decode and canonically encode the preference.
 * @property userPreferenceDao Persists the global preference row.
 * @property authorizationService Enforces READ access to model/settings at write time.
 * @property modelSettingsService Loads and checks the referenced settings profile.
 * @property transactionScope Transaction wrapper keeping write validation and persistence consistent.
 */
class DefaultConversationCompactionConfigurationService(
    private val json: Json,
    private val userPreferenceDao: UserPreferenceDao,
    private val authorizationService: AuthorizationService,
    private val modelSettingsService: ModelSettingsService,
    private val transactionScope: TransactionScope
) : ConversationCompactionConfigurationService {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultConversationCompactionConfigurationService::class.java)
    }

    override suspend fun updateConfiguration(
        userId: Long,
        rawValue: String
    ): Either<ConversationCompactionConfigurationError, Unit> = transactionScope.transaction {
        either {
            val preference = decodePreference(rawValue).bind()
            validateStructural(preference).bind()
            validateConfigurationForStorage(userId, preference).bind()

            userPreferenceDao.upsertPreference(
                userId = userId,
                internalDeviceId = null,
                clientDeviceId = null,
                key = PreferenceKeys.CONVERSATION_COMPACTION,
                value = json.encodeToString(ConversationCompactionPreference.serializer(), preference)
            )
            logger.debug("Stored conversation-compaction preference for user {}", userId)
        }
    }

    override suspend fun deleteConfiguration(
        userId: Long
    ): Either<ConversationCompactionConfigurationError, Unit> = transactionScope.transaction {
        userPreferenceDao.deletePreference(
            userId = userId,
            internalDeviceId = null,
            key = PreferenceKeys.CONVERSATION_COMPACTION
        )
        Unit.right()
    }

    /**
     * Decodes the raw preference JSON using the lenient shared codec.
     *
     * @param rawValue The raw string value of the preference.
     * @return Either an [ConversationCompactionConfigurationError.InvalidValue] for malformed JSON or
     *         the decoded preference.
     */
    private fun decodePreference(rawValue: String): Either<ConversationCompactionConfigurationError, ConversationCompactionPreference> {
        return try {
            json.decodeFromString<ConversationCompactionPreference>(rawValue).right()
        } catch (_: Exception) {
            ConversationCompactionConfigurationError.InvalidValue(
                "conversation_compaction must be a JSON object with modelId, settingsId (either may be " +
                    "null when the referenced model/settings no longer exist), instruction, and optional " +
                    "thresholdTokens, summaryLabel, enabled"
            ).left()
        }
    }

    /**
     * Applies the structural domain rules shared with the runtime path.
     *
     * Non-null ids must be positive; null ids are allowed (the referenced row no longer exists) and
     * defer the access/correctness checks to runtime, handled inside
     * [validateConfigurationForStorage].
     *
     * @param preference The decoded preference.
     * @return Either an [ConversationCompactionConfigurationError.InvalidValue] or Unit.
     */
    private fun validateStructural(
        preference: ConversationCompactionPreference
    ): Either<ConversationCompactionConfigurationError, Unit> {
        // Locals allow the null-check smart casts (the properties are public API from another module).
        val modelId = preference.modelId
        val settingsId = preference.settingsId
        if (modelId != null && modelId <= 0L) {
            return ConversationCompactionConfigurationError.InvalidValue("modelId must be positive").left()
        }
        if (settingsId != null && settingsId <= 0L) {
            return ConversationCompactionConfigurationError.InvalidValue("settingsId must be positive").left()
        }
        if (preference.instruction.isBlank()) {
            return ConversationCompactionConfigurationError.InvalidValue("instruction must not be blank").left()
        }
        if (preference.thresholdTokens <= 0L) {
            return ConversationCompactionConfigurationError.InvalidValue("thresholdTokens must be positive").left()
        }
        return Unit.right()
    }

    /**
     * Validates the non-runtime concerns of a preference.
     *
     * Runs during PUT: the owner must have READ access to the referenced model and settings, the
     * settings must exist and belong to that model, and the settings profile must be chat-like and
     * non-streaming (the auxiliary compaction call is a non-streaming chat request). The settings
     * lookup implies the model exists (settings reference an existing model). Runtime concerns (model
     * activity, provider, strategy, credential) are deliberately not checked here — they are validated
     * by the fast runtime resolver when compaction actually runs. A preference with a null
     * model/settings reference (referenced rows were deleted) cannot be checked and returns Unit so
     * it is stored as-is; at runtime it raises an invalid-configuration error only when compaction is
     * required.
     *
     * @param userId Owner of the preference, checked for READ access.
     * @param preference The decoded preference (references may be null).
     * @return Either a [ConversationCompactionConfigurationError] or Unit when the checks pass or
     *         nothing can be checked (null reference).
     */
    private suspend fun validateConfigurationForStorage(
        userId: Long,
        preference: ConversationCompactionPreference
    ): Either<ConversationCompactionConfigurationError, Unit> {
        // Locals allow the null-check smart casts (the properties are public API from another module);
        // a null reference means the rows no longer exist, so there is nothing to check here and the
        // failure is deferred to runtime (when compaction is required).
        val modelId = preference.modelId ?: return Unit.right()
        val settingsId = preference.settingsId ?: return Unit.right()

        return either {
            authorizationService.requireAccess(userId, ResourceType.MODEL, modelId, AccessMode.READ)
                .mapLeft { authorizationError ->
                    ConversationCompactionConfigurationError.AccessDenied(
                        "No READ access to compaction model $modelId: ${authorizationError::class.simpleName}"
                    )
                }
                .bind()

            authorizationService.requireAccess(userId, ResourceType.SETTINGS, settingsId, AccessMode.READ)
                .mapLeft { authorizationError ->
                    ConversationCompactionConfigurationError.AccessDenied(
                        "No READ access to compaction settings $settingsId: ${authorizationError::class.simpleName}"
                    )
                }
                .bind()

            // Model validity needs no dedicated check: settings rows reference an existing model, so a
            // successfully loaded settings profile paired with the referenced model id proves the model
            // exists. Whether that model is active is a runtime concern.
            val settings = withError({ error: GetSettingsByIdError ->
                ConversationCompactionConfigurationError.NotFound("Compaction settings $settingsId not found: $error")
            }) {
                modelSettingsService.getSettingsById(settingsId).bind()
            }
            ensure(settings.modelId == modelId) {
                ConversationCompactionConfigurationError.IncompatibleConfiguration(
                    "Compaction settings ${settings.name} belong to model ${settings.modelId}, " +
                        "not to compaction model $modelId"
                )
            }

            // Only chat-like settings may drive an auxiliary chat request, and the auxiliary call is
            // non-streaming, so a streaming-only profile is invalid regardless of the primary mode.
            // This is a static property of the settings row, so it is a write-time configuration
            // concern here rather than a runtime one.
            val nonStreaming = when (settings) {
                is ChatModelSettings -> !settings.stream
                is ResponsesModelSettings -> !settings.stream
                else -> false
            }
            ensure(nonStreaming) {
                ConversationCompactionConfigurationError.IncompatibleConfiguration(
                    "Compaction settings ${settings.name} must be chat-like (CHAT or RESPONSES) " +
                        "with stream=false, but was ${settings::class.simpleName}"
                )
            }
        }
    }
}