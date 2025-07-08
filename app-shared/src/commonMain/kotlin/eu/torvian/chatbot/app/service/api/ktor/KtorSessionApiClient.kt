package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [SessionApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiClient.safeApiCall] helper
 * to interact with the backend's session endpoints, mapping responses
 * to [Either<ApiError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorSessionApiClient(client: HttpClient) : BaseApiClient(client), SessionApi {

    override suspend fun getAllSessions(): Either<ApiError, List<ChatSessionSummary>> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions
            client.get(SessionResource())
                .body<List<ChatSessionSummary>>() // Expect a List<ChatSessionSummary> on success (HTTP 200)
        }
    }

    override suspend fun createSession(request: CreateSessionRequest): Either<ApiError, ChatSession> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions
            client.post(SessionResource()) {
                // Set the request body
                setBody(request)
            }.body<ChatSession>() // Expect a ChatSession on success (HTTP 201)
        }
    }

    override suspend fun getSessionDetails(sessionId: Long): Either<ApiError, ChatSession> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}
            client.get(SessionResource.ById(sessionId = sessionId))
                .body<ChatSession>() // Expect a ChatSession on success (HTTP 200)
        }
    }

    override suspend fun deleteSession(sessionId: Long): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}
            client.delete(SessionResource.ById(sessionId = sessionId))
                .body<Unit>() // Explicitly expect Unit body on success (HTTP 204)
        }
    }

    override suspend fun updateSessionName(sessionId: Long, request: UpdateSessionNameRequest): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/name
            client.put(SessionResource.ById.Name(SessionResource.ById( sessionId = sessionId))) {
                setBody(request)
            }.body<Unit>() // Explicitly expect Unit body on success (HTTP 200/204)
        }
    }

    override suspend fun updateSessionModel(
        sessionId: Long,
        request: UpdateSessionModelRequest
    ): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/model
            client.put(SessionResource.ById.Model(SessionResource.ById(sessionId = sessionId))) {
                setBody(request)
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }

    override suspend fun updateSessionSettings(
        sessionId: Long,
        request: UpdateSessionSettingsRequest
    ): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/settings
            client.put(SessionResource.ById.Settings(SessionResource.ById(sessionId = sessionId))) {
                setBody(request)
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }

    override suspend fun updateSessionLeafMessage(
        sessionId: Long,
        request: UpdateSessionLeafMessageRequest
    ): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/leafMessage
            client.put(SessionResource.ById.LeafMessage(SessionResource.ById(sessionId = sessionId))) {
                setBody(request)
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }

    override suspend fun updateSessionGroup(
        sessionId: Long,
        request: UpdateSessionGroupRequest
    ): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/group
            client.put(SessionResource.ById.Group(SessionResource.ById(sessionId = sessionId))) {
                setBody(request)
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }
}