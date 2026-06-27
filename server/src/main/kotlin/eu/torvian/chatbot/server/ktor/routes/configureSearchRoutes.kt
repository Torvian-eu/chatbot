package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.SearchResource
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.SearchService
import eu.torvian.chatbot.server.service.core.error.search.toApiError
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.routing.Route

/**
 * Configures routes related to cross-session search under `/api/v1/search`.
 *
 * @param searchService Service handling validation and execution of ownership-scoped searches.
 */
fun Route.configureSearchRoutes(searchService: SearchService) {
    authenticate(AuthSchemes.USER_JWT) {
        get<SearchResource.Messages> { resource ->
            val userId = call.getUserId()
            call.respondEither(searchService.searchMessages(userId, resource.query)) { error ->
                error.toApiError()
            }
        }
    }
}