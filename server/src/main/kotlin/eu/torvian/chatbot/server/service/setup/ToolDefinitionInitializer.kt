package eu.torvian.chatbot.server.service.setup

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.CreateToolError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
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

        // Create the default tools
        createWebSearchTool().bind()
        createWeatherTool().bind()
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
     * Creates the default weather tool with mock data.
     */
    private suspend fun createWeatherTool(): Either<String, Unit> = either {
        withError({ error: CreateToolError ->
            "Failed to create weather tool: ${error.toErrorMessage()}"
        }) {
            toolService.createTool(
                name = "get_weather",
                description = "Get current weather information for major cities (London, Paris, Amsterdam, Berlin, Rome, Madrid, Barcelona, Brussels, Vienna, Lisbon). This is a mock implementation for testing purposes.",
                type = ToolType.WEATHER,
                config = buildJsonObject {
                    // No specific config needed for mock weather tool
                },
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("city", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The city name (e.g., London, Paris, Amsterdam)"))
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("London"))
                                add(JsonPrimitive("Paris"))
                                add(JsonPrimitive("Amsterdam"))
                                add(JsonPrimitive("Berlin"))
                                add(JsonPrimitive("Rome"))
                                add(JsonPrimitive("Madrid"))
                                add(JsonPrimitive("Barcelona"))
                                add(JsonPrimitive("Brussels"))
                                add(JsonPrimitive("Vienna"))
                                add(JsonPrimitive("Lisbon"))
                            })
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("city"))
                    })
                },
                outputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("city", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("country", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("temperature", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                        })
                        put("temperature_unit", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("condition", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("humidity", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                        })
                        put("wind_speed", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                        })
                        put("wind_speed_unit", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                        })
                        put("description", buildJsonObject {
                            put("type", JsonPrimitive("string"))
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

