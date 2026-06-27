package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SearchApi
import eu.torvian.chatbot.common.api.resources.SearchResource
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get

/**
 * Ktor-backed implementation of [SearchApi].
 *
 * @property client Authenticated HTTP client used for search requests.
 */
class KtorSearchApiClient(
    client: HttpClient
) : BaseApiResourceClient(client), SearchApi {

    /**
     * Searches messages across all accessible sessions for the authenticated user.
     *
     * @param query Literal query string supplied by the user.
     * @return Either the API failure or the backend-provided search results.
     */
    override suspend fun searchMessages(query: String): Either<ApiResourceError, List<MessageSearchResult>> {
        return safeApiCall {
            client.get(SearchResource.Messages(query = query))
                .body<List<MessageSearchResult>>()
        }
    }
}