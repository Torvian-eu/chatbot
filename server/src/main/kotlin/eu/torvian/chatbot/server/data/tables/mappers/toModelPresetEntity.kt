package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.data.tables.ModelPresetTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed [ResultRow] from `model_presets` to a [ModelPresetEntity].
 *
 * The owner is not part of this row — it lives in `model_preset_owners` and is loaded separately,
 * mirroring [toProjectEntity]. The epoch-millis timestamp columns are converted to [Instant] here so
 * the entity (and the DTO built from it) works with the shared timestamp type.
 *
 * @receiver The result row produced by a query against [ModelPresetTable].
 * @return The corresponding [ModelPresetEntity].
 */
fun ResultRow.toModelPresetEntity(): ModelPresetEntity = ModelPresetEntity(
    id = this[ModelPresetTable.id].value,
    name = this[ModelPresetTable.name],
    displayName = this[ModelPresetTable.displayName],
    description = this[ModelPresetTable.description],
    modelId = this[ModelPresetTable.modelId]?.value,
    modelSettingsId = this[ModelPresetTable.modelSettingsId]?.value,
    createdAt = Instant.fromEpochMilliseconds(this[ModelPresetTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[ModelPresetTable.updatedAt])
)
