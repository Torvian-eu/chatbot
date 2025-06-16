package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.tables.LLMModelTable
import org.jetbrains.exposed.sql.ResultRow

/**
 * Maps an Exposed ResultRow from LLMModelTable to an LLMModel DTO.
 */
fun ResultRow.toLLMModel() = LLMModel(
    id = this[LLMModelTable.id].value,
    name = this[LLMModelTable.name],
    providerId = this[LLMModelTable.providerId].value,
    active = this[LLMModelTable.active],
    displayName = this[LLMModelTable.displayName]
)