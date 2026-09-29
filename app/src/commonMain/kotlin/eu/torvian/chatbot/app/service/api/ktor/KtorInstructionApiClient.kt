package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.InstructionApi
import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.InstructionResource
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [InstructionApi] for the user-owned instruction endpoints.
 *
 * Uses Ktor Resources for type-safe URL construction (all library operations live under
 * `/api/v1/instructions`, with the written row named by the request body, and the two link operations
 * under the role they mutate) and wraps every request in
 * [BaseApiResourceClient.safeApiCall] so failures surface as [ApiResourceError] values instead of
 * exceptions.
 *
 * @property httpClient The authenticated Ktor [HttpClient] used for all requests.
 */
class KtorInstructionApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), InstructionApi {

    override suspend fun listInstructions(): Either<ApiResourceError, List<AgentInstructionDto>> =
        safeApiCall {
            client.get(InstructionResource()).body<List<AgentInstructionDto>>()
        }

    override suspend fun getInstruction(instructionId: Long): Either<ApiResourceError, AgentInstructionDto> =
        safeApiCall {
            client.get(InstructionResource.ById(instructionId = instructionId)).body<AgentInstructionDto>()
        }

    override suspend fun deleteInstruction(instructionId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            // The server answers 204, so the response carries no body to decode.
            client.delete(InstructionResource.ById(instructionId = instructionId))
        }

    override suspend fun assignInstruction(
        roleId: Long,
        instructionId: Long
    ): Either<ApiResourceError, AgentRoleDto> =
        safeApiCall {
            client.post(linkResource(roleId, instructionId)).body<AgentRoleDto>()
        }

    override suspend fun unassignInstruction(
        roleId: Long,
        instructionId: Long
    ): Either<ApiResourceError, AgentRoleDto> =
        safeApiCall {
            client.delete(linkResource(roleId, instructionId)).body<AgentRoleDto>()
        }

    /**
     * Builds the URL of one role↔instruction link.
     *
     * @param roleId The role the link belongs to.
     * @param instructionId The linked instruction.
     * @return The nested link resource.
     */
    private fun linkResource(roleId: Long, instructionId: Long): AgentRoleResource.ById.Instructions.ByInstructionId =
        AgentRoleResource.ById.Instructions.ByInstructionId(
            parent = AgentRoleResource.ById.Instructions(
                parent = AgentRoleResource.ById(roleId = roleId)
            ),
            instructionId = instructionId
        )

    override suspend fun createInstruction(
        request: CreateInstructionRequest
    ): Either<ApiResourceError, AgentInstructionDto> =
        safeApiCall {
            client.post(InstructionResource()) {
                setBody(request)
            }.body<AgentInstructionDto>()
        }

    override suspend fun updateInstruction(
        request: UpdateInstructionRequest
    ): Either<ApiResourceError, AgentInstructionDto> =
        safeApiCall {
            client.put(InstructionResource()) {
                setBody(request)
            }.body<AgentInstructionDto>()
        }
}
