package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ToolApi
import eu.torvian.chatbot.common.api.resources.ToolResource
import eu.torvian.chatbot.common.models.api.tool.SetToolApprovalPreferenceRequest
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [ToolApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's tool endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorToolApiClient(client: HttpClient) : BaseApiResourceClient(client), ToolApi {

    override suspend fun getAllTools(): Either<ApiResourceError, List<ToolDefinition>> {
        return safeApiCall {
            client.get(ToolResource()).body<List<ToolDefinition>>()
        }
    }

    override suspend fun getToolById(toolId: Long): Either<ApiResourceError, ToolDefinition> {
        return safeApiCall {
            client.get(ToolResource.ById(toolId = toolId)).body<ToolDefinition>()
        }
    }

    override suspend fun getAllToolApprovalPreferences(): Either<ApiResourceError, List<UserToolApprovalPreference>> {
        return safeApiCall {
            client.get(ToolResource.ApprovalPreferences()).body<List<UserToolApprovalPreference>>()
        }
    }

    override suspend fun getToolApprovalPreference(toolId: Long): Either<ApiResourceError, UserToolApprovalPreference> {
        return safeApiCall {
            client.get(
                ToolResource.ApprovalPreferences.ByToolId(toolId = toolId)
            ).body<UserToolApprovalPreference>()
        }
    }

    override suspend fun setToolApprovalPreference(
        toolDefinitionId: Long,
        autoApprove: Boolean,
        conditions: String?,
        denialReason: String?
    ): Either<ApiResourceError, UserToolApprovalPreference> {
        return safeApiCall {
            client.put(
                ToolResource.ApprovalPreferences()
            ) {
                setBody(
                    SetToolApprovalPreferenceRequest(
                        toolDefinitionId = toolDefinitionId,
                        autoApprove = autoApprove,
                        conditions = conditions,
                        denialReason = denialReason
                    )
                )
            }.body<UserToolApprovalPreference>()
        }
    }

    override suspend fun deleteToolApprovalPreference(toolId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(
                ToolResource.ApprovalPreferences.ByToolId(toolId = toolId)
            ).body<Unit>()
        }
    }
}
