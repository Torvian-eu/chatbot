package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.data.ModelSettingsEntity
import eu.torvian.chatbot.server.data.tables.ModelSettingsTable
import eu.torvian.chatbot.server.data.toDomain
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps an Exposed ResultRow from ModelSettings table to a ModelSettings DTO.
 * Uses the new flexible schema with type discriminator and JSON columns.
 */
fun ResultRow.toModelSettings(): ModelSettings {
    val entity = ModelSettingsEntity(
        id = this[ModelSettingsTable.id].value,
        modelId = this[ModelSettingsTable.modelId].value,
        name = this[ModelSettingsTable.name],
        type = this[ModelSettingsTable.type],
        variableParamsJson = this[ModelSettingsTable.variableParamsJson],
        customParamsJson = this[ModelSettingsTable.customParamsJson]
    )
    return entity.toDomain()
}