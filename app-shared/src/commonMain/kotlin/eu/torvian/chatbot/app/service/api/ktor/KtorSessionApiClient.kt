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

    /**
     * Retrieves summaries for all chat sessions.
     * (E2.S3)
     *
     * @return [Either] list of session summaries or an [ApiError].
     */
    override suspend fun getAllSessions(): Either<ApiError, List<ChatSessionSummary>> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions
            client.get(SessionResource())
                .body<List<ChatSessionSummary>>() // Expect a List<ChatSessionSummary> on success (HTTP 200)
        }
    }

    /**
     * Creates a new chat session.
     * (E2.S1)
     *
     * @param request Optional name request.
     * @return [Either] the created ChatSession or an [ApiError].
     */
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

    /**
     * Retrieves full details for a specific chat session.
     * (E2.S4)
     *
     * @param sessionId The ID of the session.
     * @return [Either] the ChatSession with messages or an [ApiError].
     */
    override suspend fun getSessionDetails(sessionId: Long): Either<ApiError, ChatSession> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}
            client.get(SessionResource.ById(sessionId = sessionId))
                .body<ChatSession>() // Expect a ChatSession on success (HTTP 200)
        }
    }

    /**
     * Deletes a chat session.
     * (E2.S6)
     *
     * @param sessionId The ID of the session.
     * @return [Either] Unit on success or an [ApiError].
     */
    override suspend fun deleteSession(sessionId: Long): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}
            client.delete(SessionResource.ById(sessionId = sessionId))
                .body<Unit>() // Explicitly expect Unit body on success (HTTP 204)
        }
    }

    /**
     * Updates the name of a chat session.
     * (E2.S5)
     *
     * @param sessionId The ID of the session.
     * @param request The new name.
     * @return [Either] Unit on success or an [ApiError].
     */
    override suspend fun updateSessionName(sessionId: Long, request: UpdateSessionNameRequest): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/name
            client.put(SessionResource.ById.Name(SessionResource.ById( sessionId = sessionId))) {
                setBody(request)
            }.body<Unit>() // Explicitly expect Unit body on success (HTTP 200/204)
        }
    }

    /**
     * Updates the current model of a chat session.
     * (E4.S7)
     *
     * @param sessionId The ID of the session.
     * @param request The request with the new model ID.
     * @return [Either] Unit on success or an [ApiError].
     */
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

    /**
     * Updates the current settings profile of a chat session.
     * (E4.S7)
     *
     * @param sessionId The ID of the session.
     * @param request The request with the new settings ID.
     * @return [Either] Unit on success or an [ApiError].
     */
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

    /**
     * Updates the current leaf message ID of a chat session.
     * (E1.S5)
     *
     * @param sessionId The ID of the session.
     * @param request The request with the new leaf message ID.
     * @return [Either] Unit on success or an [ApiError].
     */
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

    /**
     * Assigns a chat session to a group or ungroups it.
     * (E6.S1, E6.S7)
     *
     * @param sessionId The ID of the session.
     * @param request The request with the new optional group ID.
     * @return [Either] Unit on success or an [ApiError].
     */
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