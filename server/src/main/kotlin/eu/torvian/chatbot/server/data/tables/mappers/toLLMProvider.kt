package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.server.data.tables.LLMProviderTable
import org.jetbrains.exposed.sql.ResultRow

/**
 * Maps an Exposed ResultRow from LLMProviderTable to an LLMProvider DTO.
 */
fun ResultRow.toLLMProvider() = LLMProvider(
    id = this[LLMProviderTable.id].value,
    apiKeyId = this[LLMProviderTable.apiKeyId],
    name = this[LLMProviderTable.name],
    description = this[LLMProviderTable.description],
    baseUrl = this[LLMProviderTable.baseUrl],
    type = this[LLMProviderTable.type]
)
