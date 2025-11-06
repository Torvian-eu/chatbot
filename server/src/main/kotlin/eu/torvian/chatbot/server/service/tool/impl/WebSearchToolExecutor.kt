package eu.torvian.chatbot.server.service.tool.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.tool.ToolExecutor
import eu.torvian.chatbot.server.service.tool.error.ToolExecutionError
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * Executor for web search tools using DuckDuckGo.
 * Performs web searches and returns formatted results.
 */
class WebSearchToolExecutor(
    private val httpClient: HttpClient
) : ToolExecutor {

    private val logger: Logger = LogManager.getLogger(WebSearchToolExecutor::class.java)

    companion object {
        private const val DUCKDUCKGO_API_URL = "https://html.duckduckgo.com/html/"
        private const val DEFAULT_MAX_RESULTS = 10
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
    }

    override suspend fun executeTool(
        toolDefinition: ToolDefinition,
        inputJson: String?
    ): Either<ToolExecutionError, String> = either {
        // Validate tool type
        ensure(toolDefinition.type == ToolType.WEB_SEARCH) {
            ToolExecutionError.InvalidConfiguration(
                "WebSearchToolExecutor can only handle WEB_SEARCH tools"
            )
        }

        // Validate configuration
        validateConfiguration(toolDefinition).bind()

        // Parse input JSON
        val input = try {
            ensure(!inputJson.isNullOrBlank()) { ToolExecutionError.InvalidInput("Missing or empty input JSON") }
            Json.parseToJsonElement(inputJson).jsonObject
        } catch (e: Exception) {
            raise(ToolExecutionError.InvalidInput(
                "Failed to parse input JSON: ${e.message ?: "Unknown error"}"
            ))
        }

        // Extract query from input
        val query = input["query"]?.jsonPrimitive?.contentOrNull
        ensure(!query.isNullOrBlank()) {
            ToolExecutionError.InvalidInput(
                "Missing or empty required parameter 'query'"
            )
        }

        // Extract optional parameters
        val maxResults = input["max_results"]?.jsonPrimitive?.intOrNull
            ?: toolDefinition.config["default_max_results"]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_MAX_RESULTS

        val timeoutSeconds = toolDefinition.config["timeout_seconds"]?.jsonPrimitive?.longOrNull
            ?: DEFAULT_TIMEOUT_SECONDS

        logger.info("Executing web search: query='$query', maxResults=$maxResults")

        // Perform search
        val searchResults = try {
            withTimeout(timeoutSeconds.seconds) {
                performDuckDuckGoSearch(query, maxResults)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn("Web search timed out after ${timeoutSeconds}s", e)
            raise(ToolExecutionError.Timeout(
                "Search request timed out after $timeoutSeconds seconds"
            ))
        } catch (e: Exception) {
            logger.error("Web search failed", e)
            raise(ToolExecutionError.ExternalServiceError(
                "Search failed: ${e.message ?: "Unknown error"}"
            ))
        }

        logger.info("Web search completed: ${searchResults.size} results")

        // Format results as JSON
        buildJsonObject {
            put("query", query)
            put("results", JsonArray(searchResults.map { result ->
                buildJsonObject {
                    put("title", result.title)
                    put("url", result.url)
                    put("snippet", result.snippet)
                }
            }))
            put("count", searchResults.size)
        }.toString()
    }

    override fun validateConfiguration(
        toolDefinition: ToolDefinition
    ): Either<ToolExecutionError.InvalidConfiguration, Unit> {
        if (toolDefinition.type != ToolType.WEB_SEARCH) {
            return ToolExecutionError.InvalidConfiguration(
                "Tool type must be WEB_SEARCH"
            ).left()
        }

        // Validate max_results if present
        val maxResults = toolDefinition.config["default_max_results"]?.jsonPrimitive?.intOrNull
        if (maxResults != null && (maxResults < 1 || maxResults > 100)) {
            return ToolExecutionError.InvalidConfiguration(
                "default_max_results must be between 1 and 100"
            ).left()
        }

        // Validate timeout if present
        val timeout = toolDefinition.config["timeout_seconds"]?.jsonPrimitive?.longOrNull
        if (timeout != null && (timeout < 1 || timeout > 300)) {
            return ToolExecutionError.InvalidConfiguration(
                "timeout_seconds must be between 1 and 300"
            ).left()
        }

        return Unit.right()
    }

    /**
     * Performs a web search using DuckDuckGo HTML search.
     * @param query The search query.
     * @param maxResults Maximum number of results to return.
     * @return A list of search results.
     */
    private suspend fun performDuckDuckGoSearch(
        query: String,
        maxResults: Int
    ): List<SearchResult> {
        // DuckDuckGo HTML search endpoint
        val response: HttpResponse = httpClient.post(DUCKDUCKGO_API_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("q=${query.encodeURLParameter()}")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }

        if (!response.status.isSuccess()) {
            throw Exception("DuckDuckGo search failed with status: ${response.status}")
        }

        val html = response.bodyAsText()

        // Parse HTML to extract search results
        // This is a simplified parser - in production, consider using a proper HTML parser
        return parseSearchResults(html, maxResults)
    }

    /**
     * Parses DuckDuckGo HTML search results.
     * Note: This is a simplified implementation. For production use, consider a proper HTML parser.
     */
    private fun parseSearchResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Simple regex-based parsing (note: fragile, may break if DDG changes their HTML)
        // In production, use a proper HTML parser like jsoup or kotlinx.html
        val resultPattern = """<div class="result__body">.*?<a.*?href="(.*?)".*?>(.*?)</a>.*?<a class="result__snippet".*?>(.*?)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val matches = resultPattern.findAll(html)

        for (match in matches.take(maxResults)) {
            val url = match.groupValues[1].decodeURLPart()
            val title = match.groupValues[2].replace(Regex("<.*?>"), "").trim()
            val snippet = match.groupValues[3].replace(Regex("<.*?>"), "").trim()

            results.add(SearchResult(
                title = title,
                url = url,
                snippet = snippet
            ))
        }

        // If parsing fails or returns no results, return a fallback message
        if (results.isEmpty()) {
            logger.warn("No search results parsed from DuckDuckGo response")
            results.add(SearchResult(
                title = "No results found",
                url = "",
                snippet = "The search did not return any results."
            ))
        }

        return results
    }

    /**
     * Data class representing a search result.
     */
    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )
}

