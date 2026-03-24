package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.tool.ToolCall
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [SessionApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's session endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorSessionApiClient(client: HttpClient) : BaseApiResourceClient(client), SessionApi {

    override suspend fun getAllSessions(): Either<ApiResourceError, List<ChatSessionSummary>> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions
            client.get(SessionResource())
                .body<List<ChatSessionSummary>>() // Expect a List<ChatSessionSummary> on success (HTTP 200)
        }
    }

    override suspend fun createSession(name: String): Either<ApiResourceError, ChatSession> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions
            client.post(SessionResource()) {
                // Set the request body
                setBody(CreateSessionRequest(name = name))
            }.body<ChatSession>() // Expect a ChatSession on success (HTTP 201)
        }
    }

    override suspend fun getSessionDetails(sessionId: Long): Either<ApiResourceError, ChatSession> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}
            client.get(SessionResource.ById(sessionId = sessionId))
                .body<ChatSession>() // Expect a ChatSession on success (HTTP 200)
        }
    }

    override suspend fun deleteSession(sessionId: Long): Either<ApiResourceError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}
            client.delete(SessionResource.ById(sessionId = sessionId))
                .body<Unit>() // Explicitly expect Unit body on success (HTTP 204)
        }
    }

    override suspend fun updateSessionName(sessionId: Long, name: String): Either<ApiResourceError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/name
            client.put(SessionResource.ById.Name(SessionResource.ById(sessionId = sessionId))) {
                setBody(UpdateSessionNameRequest(name = name))
            }.body<Unit>() // Explicitly expect Unit body on success (HTTP 200/204)
        }
    }

    override suspend fun updateSessionModel(
        sessionId: Long,
        modelId: Long?,
        autoSelectFirstAvailableSettings: Boolean
    ): Either<ApiResourceError, UpdateSessionModelResponse> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/model
            client.put(SessionResource.ById.Model(SessionResource.ById(sessionId = sessionId))) {
                setBody(
                    UpdateSessionModelRequest(
                        modelId = modelId,
                        autoSelectFirstAvailableSettings = autoSelectFirstAvailableSettings
                    )
                )
            }.body<UpdateSessionModelResponse>()
        }
    }

    override suspend fun updateSessionSettings(
        sessionId: Long,
        settingsId: Long?
    ): Either<ApiResourceError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/settings
            client.put(SessionResource.ById.Settings(SessionResource.ById(sessionId = sessionId))) {
                setBody(UpdateSessionSettingsRequest(settingsId = settingsId))
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }

    override suspend fun updateSessionLeafMessage(
        sessionId: Long,
        leafMessageId: Long?
    ): Either<ApiResourceError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/leafMessage
            client.put(SessionResource.ById.LeafMessage(SessionResource.ById(sessionId = sessionId))) {
                setBody(UpdateSessionLeafMessageRequest(leafMessageId = leafMessageId))
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }

    override suspend fun updateSessionGroup(
        sessionId: Long,
        groupId: Long?
    ): Either<ApiResourceError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/group
            client.put(SessionResource.ById.Group(SessionResource.ById(sessionId = sessionId))) {
                setBody(UpdateSessionGroupRequest(groupId = groupId))
            }.body<Unit>() // Expect Unit body (HTTP 200/204)
        }
    }

    override suspend fun cloneSession(
        sessionId: Long,
        name: String
    ): Either<ApiResourceError, ChatSession> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/clone
            client.post(SessionResource.ById.Clone(SessionResource.ById(sessionId = sessionId))) {
                setBody(CloneSessionRequest(name = name))
            }.body<ChatSession>() // Expect a ChatSession on success (HTTP 201)
        }
    }

    override suspend fun getSessionToolCalls(sessionId: Long): Either<ApiResourceError, List<ToolCall>> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources: /api/v1/sessions/{sessionId}/toolcalls
            client.get(SessionResource.ById.ToolCalls(SessionResource.ById(sessionId = sessionId)))
                .body<List<ToolCall>>() // Expect a List<ToolCall> on success (HTTP 200)
        }
    }
}