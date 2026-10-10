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
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.llm.chatStreamFlag
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [CompactionPreferenceService].
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
 * A null model/settings reference is legitimate only while `automaticCompactionEnabled = false`: a
 * preference with automatic compaction disabled never compacts automatically, so its references are
 * not checked and it stays storable as the way out of a half-configured state (a manual request then
 * fails with a typed server error). A preference with automatic compaction enabled must carry positive
 * ids, a non-blank instruction and a positive threshold and is rejected here before storage, so a turn
 * rejected during preparation for an enabled preference with null ids can only come from a legacy or
 * hand-edited row.
 *
 * @property json Shared JSON codec used to decode and canonically encode the preference.
 * @property userPreferenceDao Persists the global preference row.
 * @property authorizationService Enforces READ access to model/settings at write time.
 * @property modelSettingsService Loads and checks the referenced settings profile.
 * @property transactionScope Transaction wrapper keeping write validation and persistence consistent.
 */
class DefaultCompactionPreferenceService(
    private val json: Json,
    private val userPreferenceDao: UserPreferenceDao,
    private val authorizationService: AuthorizationService,
    private val modelSettingsService: ModelSettingsService,
    private val transactionScope: TransactionScope
) : CompactionPreferenceService {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultCompactionPreferenceService::class.java)
    }

    override suspend fun updateConfiguration(
        userId: Long,
        rawValue: String
    ): Either<CompactionPreferenceError, Unit> = transactionScope.transaction {
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
    ): Either<CompactionPreferenceError, Unit> = transactionScope.transaction {
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
     * @return Either an [CompactionPreferenceError.InvalidValue] for malformed JSON or
     *         the decoded preference.
     */
    private fun decodePreference(rawValue: String): Either<CompactionPreferenceError, ConversationCompactionPreference> {
        return try {
            json.decodeFromString<ConversationCompactionPreference>(rawValue).right()
        } catch (_: Exception) {
            CompactionPreferenceError.InvalidValue(
                "conversation_compaction must be a JSON object with modelId, settingsId (both required " +
                    "while automaticCompactionEnabled is true, and either may be null once it is false), " +
                    "instruction, and optional thresholdTokens, summaryLabel, automaticCompactionEnabled"
            ).left()
        }
    }

    /**
     * Applies the structural domain rules of a stored preference.
     *
     * An enabled preference must name both auxiliary references, and any non-null id must be positive.
     * A preference with automatic compaction disabled may omit them: it never compacts automatically,
     * so a missing reference cannot make it unusable — a manual request then fails because the
     * configuration is incomplete.
     * Both cases require a non-blank instruction and a positive threshold.
     *
     * @param preference The decoded preference.
     * @return Either an [CompactionPreferenceError.InvalidValue] or Unit.
     */
    private fun validateStructural(
        preference: ConversationCompactionPreference
    ): Either<CompactionPreferenceError, Unit> {
        // Locals allow the null-check smart casts (the properties are public API from another module).
        val modelId = preference.modelId
        val settingsId = preference.settingsId
        // The flag is evaluated before the ids so a disabled preference is never asked for a compactor,
        // mirroring the runtime resolver, which treats a turn with automatic compaction disabled as a
        // usable-or-unusable configuration that never rejects the turn.
        if (preference.automaticCompactionEnabled) {
            if (modelId == null) {
                return CompactionPreferenceError.InvalidValue(
                    "modelId is required while automaticCompactionEnabled is true"
                ).left()
            }
            if (settingsId == null) {
                return CompactionPreferenceError.InvalidValue(
                    "settingsId is required while automaticCompactionEnabled is true"
                ).left()
            }
        }
        if (modelId != null && modelId <= 0L) {
            return CompactionPreferenceError.InvalidValue("modelId must be positive").left()
        }
        if (settingsId != null && settingsId <= 0L) {
            return CompactionPreferenceError.InvalidValue("settingsId must be positive").left()
        }
        if (preference.instruction.isBlank()) {
            return CompactionPreferenceError.InvalidValue("instruction must not be blank").left()
        }
        if (preference.thresholdTokens <= 0L) {
            return CompactionPreferenceError.InvalidValue("thresholdTokens must be positive").left()
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
     * by the fast runtime resolver when compaction actually runs. A null model/settings reference only
     * reaches this point while automatic compaction is disabled, so there is never a compactor to check.
     *
     * @param userId Owner of the preference, checked for READ access.
     * @param preference The decoded preference (references may be null while automatic compaction is
     *            disabled).
     * @return Either a [CompactionPreferenceError] or Unit when the checks pass or
     *         nothing can be checked (null reference while automatic compaction is disabled).
     */
    private suspend fun validateConfigurationForStorage(
        userId: Long,
        preference: ConversationCompactionPreference
    ): Either<CompactionPreferenceError, Unit> {
        // Locals allow the null-check smart casts (the properties are public API from another module).
        // Only a preference with automatic compaction disabled can still carry a null reference here,
        // and it never resolves a compactor, so there is nothing to check.
        val modelId = preference.modelId ?: return Unit.right()
        val settingsId = preference.settingsId ?: return Unit.right()

        return either {
            authorizationService.requireAccess(userId, ResourceType.MODEL, modelId, AccessMode.READ)
                .mapLeft { authorizationError ->
                    CompactionPreferenceError.AccessDenied(
                        "No READ access to compaction model $modelId: ${authorizationError::class.simpleName}"
                    )
                }
                .bind()

            authorizationService.requireAccess(userId, ResourceType.SETTINGS, settingsId, AccessMode.READ)
                .mapLeft { authorizationError ->
                    CompactionPreferenceError.AccessDenied(
                        "No READ access to compaction settings $settingsId: ${authorizationError::class.simpleName}"
                    )
                }
                .bind()

            // Model validity needs no dedicated check: settings rows reference an existing model, so a
            // successfully loaded settings profile paired with the referenced model id proves the model
            // exists. Whether that model is active is a runtime concern.
            val settings = withError({ error: GetSettingsByIdError ->
                CompactionPreferenceError.NotFound("Compaction settings $settingsId not found: $error")
            }) {
                modelSettingsService.getSettingsById(settingsId).bind()
            }
            ensure(settings.modelId == modelId) {
                CompactionPreferenceError.IncompatibleConfiguration(
                    "Compaction settings ${settings.name} belong to model ${settings.modelId}, " +
                        "not to compaction model $modelId"
                )
            }

            // Only chat-like settings may drive an auxiliary chat request, and the auxiliary call is
            // non-streaming, so a streaming-only profile is invalid regardless of the primary mode.
            // This is a static property of the settings row, so it is a write-time configuration
            // concern here rather than a runtime one. The shared helper answers both questions with the
            // same rules turn preparation and role-attach validation use.
            ensure(chatStreamFlag(settings) == false) {
                CompactionPreferenceError.IncompatibleConfiguration(
                    "Compaction settings ${settings.name} must be chat-like (CHAT or RESPONSES) " +
                        "with stream=false, but was ${settings::class.simpleName}"
                )
            }
        }
    }
}