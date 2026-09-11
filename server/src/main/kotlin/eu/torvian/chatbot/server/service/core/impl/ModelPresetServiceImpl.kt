package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.ModelPresetOwnershipDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.preset.ModelPresetError as ModelPresetDaoError
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.CreateModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.DeleteModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.ModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.UpdateModelPresetError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [ModelPresetService] providing owner-scoped model-preset CRUD operations.
 *
 * Uses Arrow's `either { }`/`ensure`/`withError` pattern for typed logical errors and wraps all
 * operations in the shared [TransactionScope], mirroring [ProjectServiceImpl]. Validation and
 * persistence therefore run in one transaction: a rejected request persists nothing.
 *
 * Reference validation deliberately uses the **accessibility** reads (ownership row *or* group access
 * row) rather than plain existence, because a preset may only reference a model/settings profile the
 * user can `READ`. The preset layer itself puts no restriction on the settings' model type: any
 * [eu.torvian.chatbot.common.models.llm.LLMModelType] is accepted here and chat capability is enforced
 * where a preset drives an agent-role turn.
 *
 * @property modelPresetDao DAO for the `model_presets` table.
 * @property modelPresetOwnershipDao DAO for the `model_preset_owners` table (per-user ownership).
 * @property modelDao DAO used to validate the model reference (existence and `READ` accessibility).
 * @property settingsDao DAO used to validate the settings reference (existence, `READ` accessibility
 *            and agreement with the preset's model).
 * @property transactionScope Transaction wrapper that keeps validation + persistence atomic.
 */
class ModelPresetServiceImpl(
    private val modelPresetDao: ModelPresetDao,
    private val modelPresetOwnershipDao: ModelPresetOwnershipDao,
    private val modelDao: ModelDao,
    private val settingsDao: SettingsDao,
    private val transactionScope: TransactionScope
) : ModelPresetService {

    companion object {
        /** Logger used for service-level diagnostics. */
        private val logger: Logger = LogManager.getLogger(ModelPresetServiceImpl::class.java)
    }

    override suspend fun getAllPresetsForUser(userId: Long): List<ModelPresetDto> =
        transactionScope.transaction {
            logger.debug("Retrieving model presets for user $userId")
            modelPresetDao.getAllPresetsForUser(userId).map { it.toDto() }
        }

    override suspend fun getPresetById(
        userId: Long,
        presetId: Long
    ): Either<ModelPresetError.NotFound, ModelPresetDto> =
        transactionScope.transaction {
            either {
                loadOwnedPreset(userId, presetId, ModelPresetError.NotFound(presetId)).toDto()
            }
        }

    override suspend fun createPreset(
        userId: Long,
        request: CreateModelPresetRequest
    ): Either<CreateModelPresetError, ModelPresetDto> = transactionScope.transaction {
        either {
            // Names are stored trimmed so "  smart_model " and "smart_model" cannot coexist as two
            // presets of the same owner while looking identical to the user.
            val name = request.name.trim()
            logger.info("Creating model preset '$name' for user $userId")

            validateName(name) { reason -> CreateModelPresetError.InvalidName(name, reason) }
            // Names are unique per owner user (not globally), so the check is owner-scoped.
            ensure(!modelPresetDao.presetNameExistsForUser(userId, name)) {
                CreateModelPresetError.NameAlreadyExists(name)
            }

            validateReferences(
                userId = userId,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId,
                modelNotFound = { modelId -> CreateModelPresetError.ModelNotFound(modelId) },
                settingsNotFound = { settingsId -> CreateModelPresetError.SettingsNotFound(settingsId) },
                settingsModelMismatch = { settingsId, settingsModelId, presetModelId ->
                    CreateModelPresetError.SettingsModelMismatch(settingsId, settingsModelId, presetModelId)
                }
            )

            val entity = modelPresetDao.insertPreset(
                name = name,
                displayName = request.displayName,
                description = request.description,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId
            )

            // The ownership link is written atomically with the row, so a preset can never exist
            // without an owner (and therefore never be invisible to every user).
            withError({ ownershipError: SetOwnerError ->
                CreateModelPresetError.OwnerInsertFailed(ownershipError.toString())
            }) {
                modelPresetOwnershipDao.setOwner(entity.id, userId).bind()
            }

            logger.info("Created model preset '$name' (id ${entity.id}) for user $userId")
            entity.toDto()
        }
    }

    override suspend fun updatePreset(
        userId: Long,
        presetId: Long,
        request: UpdateModelPresetRequest
    ): Either<UpdateModelPresetError, ModelPresetDto> = transactionScope.transaction {
        either {
            val name = request.name.trim()
            logger.info("Updating model preset $presetId for user $userId")

            val existing = loadOwnedPreset(userId, presetId, UpdateModelPresetError.NotFound(presetId))

            validateName(name) { reason -> UpdateModelPresetError.InvalidName(name, reason) }
            // The rename check only has to run when the name actually changes: the preset being updated
            // would otherwise be its own collision.
            if (name != existing.name) {
                ensure(!modelPresetDao.presetNameExistsForUser(userId, name)) {
                    UpdateModelPresetError.NameAlreadyExists(name)
                }
            }

            validateReferences(
                userId = userId,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId,
                modelNotFound = { modelId -> UpdateModelPresetError.ModelNotFound(modelId) },
                settingsNotFound = { settingsId -> UpdateModelPresetError.SettingsNotFound(settingsId) },
                settingsModelMismatch = { settingsId, settingsModelId, presetModelId ->
                    UpdateModelPresetError.SettingsModelMismatch(settingsId, settingsModelId, presetModelId)
                }
            )

            val updated = existing.copy(
                name = name,
                displayName = request.displayName,
                description = request.description,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId
            )

            withError({ _: ModelPresetDaoError.NotFound -> UpdateModelPresetError.NotFound(presetId) }) {
                modelPresetDao.updatePreset(updated).bind()
            }

            logger.info("Updated model preset $presetId for user $userId")
            // `updated_at` is advanced by the DAO; re-read the row so the echoed DTO reports the value
            // actually persisted instead of the stale in-memory entity.
            loadOwnedPreset(userId, presetId, UpdateModelPresetError.NotFound(presetId)).toDto()
        }
    }

    override suspend fun deletePreset(
        userId: Long,
        presetId: Long
    ): Either<DeleteModelPresetError, Unit> = transactionScope.transaction {
        either {
            logger.info("Deleting model preset $presetId for user $userId")

            // The DAO's delete is id-keyed only, so ownership is checked here (the NotFound collapse
            // hides the existence of foreign presets).
            loadOwnedPreset(userId, presetId, DeleteModelPresetError.NotFound(presetId))

            withError({ _: ModelPresetDaoError.NotFound -> DeleteModelPresetError.NotFound(presetId) }) {
                modelPresetDao.deletePreset(presetId).bind()
            }

            logger.info("Deleted model preset $presetId for user $userId")
        }
    }

    // --- Validation helpers ---

    /**
     * Validates the preset-name shape (non-blank, at most [MAX_MODEL_PRESET_NAME_LENGTH] characters).
     *
     * @param name The already-trimmed name to validate.
     * @param invalidName Factory building the caller's invalid-name error.
     * @return `null` on success or an error of type `E` via the raise scope.
     */
    private fun <E> Raise<E>.validateName(name: String, invalidName: (reason: String) -> E) {
        ensure(name.isNotBlank()) {
            invalidName("Model preset name cannot be blank")
        }
        ensure(name.length <= MAX_MODEL_PRESET_NAME_LENGTH) {
            invalidName("Model preset name cannot exceed $MAX_MODEL_PRESET_NAME_LENGTH characters")
        }
    }

    /**
     * Validates the two optional references of a preset write.
     *
     * Each reference is checked only when provided: a preset may legitimately exist without a model
     * and/or without a settings profile (it simply cannot drive a turn), so a null reference is never
     * an error. A non-null reference must be **`READ`-accessible** to [userId] — the same
     * accessibility rule the model/settings listing uses, which also covers group-shared resources —
     * and a non-null settings reference must belong to the preset's model whenever that model is also
     * present. The settings' model type is deliberately not restricted here.
     *
     * @param userId User whose accessible model/settings sets are required.
     * @param modelId The requested model reference, or null.
     * @param modelSettingsId The requested settings reference, or null.
     * @param modelNotFound Factory building the caller's model-not-found error.
     * @param settingsNotFound Factory building the caller's settings-not-found error.
     * @param settingsModelMismatch Factory building the caller's settings/model mismatch error.
     * @return `null` on success or an error of type `E` via the raise scope.
     */
    private suspend fun <E> Raise<E>.validateReferences(
        userId: Long,
        modelId: Long?,
        modelSettingsId: Long?,
        modelNotFound: (modelId: Long) -> E,
        settingsNotFound: (settingsId: Long) -> E,
        settingsModelMismatch: (settingsId: Long, settingsModelId: Long, presetModelId: Long) -> E
    ) {
        // Each read is only performed when the corresponding reference is present, so a preset write
        // without references costs no extra queries.
        val accessibleModelIds = if (modelId != null) {
            modelDao.getAllAccessibleModels(userId, AccessMode.READ).map { it.id }.toSet()
        } else {
            emptySet()
        }
        if (modelId != null && modelId !in accessibleModelIds) {
            raise(modelNotFound(modelId))
        }

        if (modelSettingsId != null) {
            val accessibleSettings = settingsDao
                .getAllAccessibleSettings(userId, AccessMode.READ)
                .firstOrNull { it.id == modelSettingsId }
                ?: raise(settingsNotFound(modelSettingsId))

            // The two references must describe the same model: a preset is "this model, with this
            // profile of it". A null model reference means the preset binds only the profile, so there
            // is nothing to compare against.
            if (modelId != null) {
                ensure(accessibleSettings.modelId == modelId) {
                    settingsModelMismatch(modelSettingsId, accessibleSettings.modelId, modelId)
                }
            }
        }
    }

    // --- Ownership helpers ---

    /**
     * Loads a model preset and verifies that [userId] owns it.
     *
     * Ownership mismatches are reported as the provided [notFoundError] so the service never leaks the
     * existence of presets owned by other users, and the caller's error surface stays uniform.
     *
     * @param userId The requesting user.
     * @param presetId The preset to load.
     * @param notFoundError The error raised when the preset is missing or not owned.
     * @return The [ModelPresetEntity] when owned, or an error via the raise scope.
     */
    private suspend fun <E> Raise<E>.loadOwnedPreset(userId: Long, presetId: Long, notFoundError: E): ModelPresetEntity {
        // Ownership is resolved through the owner table (shared by the list/name reads) rather than
        // through getPresetById, so one lookup both asserts existence and checks the owner; a missing
        // owner row and a foreign owner collapse to the same not-found outcome.
        return modelPresetDao.getPresetsByIdsForUser(userId, listOf(presetId)).singleOrNull()
            ?: raise(notFoundError)
    }

    /**
     * Converts a stored preset row into its wire representation.
     *
     * @receiver The stored preset row.
     * @return The corresponding [ModelPresetDto], including the server-managed timestamps.
     */
    private fun ModelPresetEntity.toDto(): ModelPresetDto = ModelPresetDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
