package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.tables.ModelSettingsTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.ResultRow

/**
 * Maps an Exposed ResultRow from ModelSettings table to a ModelSettings DTO.
 */
fun ResultRow.toModelSettings() = ModelSettings(
    id = this[ModelSettingsTable.id].value,
    modelId = this[ModelSettingsTable.modelId].value,
    name = this[ModelSettingsTable.name],
    systemMessage = this[ModelSettingsTable.systemMessage],
    temperature = this[ModelSettingsTable.temperature],
    maxTokens = this[ModelSettingsTable.maxTokens],
    customParams = this[ModelSettingsTable.customParams]?.let { customParamsJson ->
        Json.decodeFromString<JsonObject>(customParamsJson)
    }
)