package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.GroupApi
import eu.torvian.chatbot.common.api.resources.GroupResource
import eu.torvian.chatbot.common.api.resources.GroupResource.ById
import eu.torvian.chatbot.common.models.api.core.CreateGroupRequest
import eu.torvian.chatbot.common.models.api.core.RenameGroupRequest
import eu.torvian.chatbot.common.models.core.ChatGroup
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [GroupApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's group endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorGroupApiClient(client: HttpClient) : BaseApiResourceClient(client), GroupApi {

    override suspend fun getAllGroups(): Either<ApiResourceError, List<ChatGroup>> {
        return safeApiCall {
            client.get(GroupResource()).body<List<ChatGroup>>()
        }
    }

    override suspend fun createGroup(name: String): Either<ApiResourceError, ChatGroup> {
        return safeApiCall {
            client.post(GroupResource()) {
                setBody(CreateGroupRequest(name = name))
            }.body<ChatGroup>()
        }
    }

    override suspend fun renameGroup(groupId: Long, name: String): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.put(ById(groupId = groupId)) {
                setBody(RenameGroupRequest(name = name))
            }.body<Unit>()
        }
    }

    override suspend fun deleteGroup(groupId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(ById(groupId = groupId)).body<Unit>()
        }
    }
}