package eu.torvian.chatbot.server.service.setup

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.CreateToolError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.serialization.json.*

/**
 * Initializer responsible for setting up default tool definitions.
 *
 * This initializer creates essential tools that should be available by default,
 * such as the web search tool using DuckDuckGo.
 */
class ToolDefinitionInitializer(
    private val toolService: ToolService,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val transactionScope: TransactionScope
) : DataInitializer {

    override val name: String = "Tool Definitions"

    override suspend fun isInitialized(): Boolean {
        return transactionScope.transaction {
            toolDefinitionDao.getAllToolDefinitions().isNotEmpty()
        }
    }

    override suspend fun initialize(): Either<String, Unit> = either {
        // Check if tools already exist
        val existingTools = transactionScope.transaction {
            toolDefinitionDao.getAllToolDefinitions()
        }

        if (existingTools.isNotEmpty()) {
            return@either
        }

        // Create the default web search tool
        createWebSearchTool().bind()
    }

    /**
     * Creates the default web search tool using DuckDuckGo.
     */
    private suspend fun createWebSearchTool(): Either<String, Unit> = either {
        withError({ error: CreateToolError ->
            "Failed to create web search tool: ${error.toErrorMessage()}"
        }) {
            toolService.createTool(
                name = "web_search",
                description = "Search the web for current information using DuckDuckGo",
                type = ToolType.WEB_SEARCH,
                config = buildJsonObject {
                    put("search_engine", JsonPrimitive("duckduckgo"))
                    put("max_results", JsonPrimitive(5))
                },
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The search query"))
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("query"))
                    })
                },
                outputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("results", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put("properties", buildJsonObject {
                                    put("title", buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                    })
                                    put("url", buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                    })
                                    put("snippet", buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                    })
                                })
                            })
                        })
                    })
                },
                isEnabled = true
            ).bind()
        }
    }

    /**
     * Converts a CreateToolError to a human-readable error message.
     */
    private fun CreateToolError.toErrorMessage(): String = when (this) {
        is CreateToolError.DuplicateName -> "Tool with name '$name' already exists"
        is CreateToolError.InvalidName -> message
        is CreateToolError.InvalidDescription -> message
        is CreateToolError.InvalidInputSchema -> message
        is CreateToolError.InvalidConfig -> message
        is CreateToolError.PersistenceError -> message
    }
}

