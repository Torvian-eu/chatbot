package eu.torvian.chatbot.server.testutils.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.tool.ToolExecutor
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stub implementation of [ToolExecutor] for web search tool testing.
 * Returns mock search results without making any real HTTP requests.
 */
class WebSearchToolExecutorStub : ToolExecutor {

    override suspend fun executeTool(
        toolDefinition: ToolDefinition,
        inputJson: String?
    ): Either<ToolExecutionError, String> {
        // Return mock results
        val mockResults = buildJsonObject {
            put("query", "test query")
            put("results", JsonArray(listOf(
                buildJsonObject {
                    put("title", "Test Result 1")
                    put("url", "https://example.com/1")
                    put("snippet", "This is a test search result.")
                },
                buildJsonObject {
                    put("title", "Test Result 2")
                    put("url", "https://example.com/2")
                    put("snippet", "This is another test search result.")
                }
            )))
            put("count", 2)
        }.toString()

        return mockResults.right()
    }

    override fun validateConfiguration(
        toolDefinition: ToolDefinition
    ): Either<ToolExecutionError.InvalidConfiguration, Unit> {
        if (toolDefinition.type != ToolType.WEB_SEARCH) {
            return ToolExecutionError.InvalidConfiguration(
                "Tool type must be WEB_SEARCH"
            ).left()
        }
        return Unit.right()
    }
}

