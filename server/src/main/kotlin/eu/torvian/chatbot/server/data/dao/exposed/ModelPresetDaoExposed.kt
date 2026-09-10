package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.error.preset.ModelPresetError
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.data.tables.ModelPresetOwnersTable
import eu.torvian.chatbot.server.data.tables.ModelPresetTable
import eu.torvian.chatbot.server.data.tables.mappers.toModelPresetEntity
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of the [ModelPresetDao].
 *
 * All operations are single-row reads/writes on `model_presets`. The owner lives in
 * `model_preset_owners` and is loaded through [ModelPresetOwnershipDao], so this class stays a plain
 * table projection. `created_at`/`updated_at` are managed here (set on insert, `updated_at` advanced
 * on update) so no caller can forget them.
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class ModelPresetDaoExposed(
    private val transactionScope: TransactionScope
) : ModelPresetDao {

    override suspend fun getAllPresetsForUser(userId: Long): List<ModelPresetEntity> =
        transactionScope.transaction {
            ModelPresetTable
                .join(
                    ModelPresetOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ModelPresetTable.id eq ModelPresetOwnersTable.presetId }
                )
                .selectAll()
                .where { ModelPresetOwnersTable.userId eq userId }
                // Deterministic listing: id ascending (the Stage-3 tool contract and the REST listing
                // share this order; the client may re-sort by name for display).
                .orderBy(ModelPresetTable.id)
                .map { it.toModelPresetEntity() }
        }

    override suspend fun getPresetById(id: Long): Either<ModelPresetError.NotFound, ModelPresetEntity> =
        transactionScope.transaction {
            either {
                val entity = ModelPresetTable.selectAll().where { ModelPresetTable.id eq id }
                    .singleOrNull()
                    ?.toModelPresetEntity()
                ensure(entity != null) { ModelPresetError.NotFound(id) }
                entity
            }
        }

    override suspend fun getPresetsByIdsForUser(userId: Long, presetIds: List<Long>): List<ModelPresetEntity> =
        transactionScope.transaction {
            if (presetIds.isEmpty()) return@transaction emptyList()
            val entitiesById = ModelPresetTable
                .join(
                    ModelPresetOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ModelPresetTable.id eq ModelPresetOwnersTable.presetId }
                )
                .selectAll()
                .where {
                    (ModelPresetOwnersTable.userId eq userId) and (ModelPresetTable.id inList presetIds)
                }
                .map { it.toModelPresetEntity() }
                .associateBy { it.id }
            // SQL does not guarantee IN-list order; restore the caller's order where present.
            presetIds.mapNotNull { entitiesById[it] }
        }

    override suspend fun presetNameExistsForUser(userId: Long, name: String): Boolean =
        transactionScope.transaction {
            ModelPresetTable
                .join(
                    ModelPresetOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ModelPresetTable.id eq ModelPresetOwnersTable.presetId }
                )
                .selectAll()
                .where { (ModelPresetOwnersTable.userId eq userId) and (ModelPresetTable.name eq name) }
                .count() > 0
        }

    override suspend fun insertPreset(
        name: String,
        displayName: String?,
        description: String,
        modelId: Long?,
        modelSettingsId: Long?
    ): ModelPresetEntity =
        transactionScope.transaction {
            val now = System.currentTimeMillis()
            val insertStatement = ModelPresetTable.insert {
                it[ModelPresetTable.name] = name
                it[ModelPresetTable.displayName] = displayName
                it[ModelPresetTable.description] = description
                it[ModelPresetTable.modelId] = modelId
                it[ModelPresetTable.modelSettingsId] = modelSettingsId
                it[ModelPresetTable.createdAt] = now
                it[ModelPresetTable.updatedAt] = now
            }
            insertStatement.resultedValues?.first()?.toModelPresetEntity()
                ?: throw IllegalStateException("Failed to retrieve newly inserted model preset")
        }

    override suspend fun updatePreset(preset: ModelPresetEntity): Either<ModelPresetError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ModelPresetTable.update({ ModelPresetTable.id eq preset.id }) {
                    it[ModelPresetTable.name] = preset.name
                    it[ModelPresetTable.displayName] = preset.displayName
                    it[ModelPresetTable.description] = preset.description
                    it[ModelPresetTable.modelId] = preset.modelId
                    it[ModelPresetTable.modelSettingsId] = preset.modelSettingsId
                    it[ModelPresetTable.updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { ModelPresetError.NotFound(preset.id) }
            }
        }

    override suspend fun deletePreset(id: Long): Either<ModelPresetError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ModelPresetTable.deleteWhere { ModelPresetTable.id eq id }
                ensure(deletedCount != 0) { ModelPresetError.NotFound(id) }
            }
        }
}
