package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.InstructionResource
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configures routes related to user-owned instructions (/api/v1/instructions) using Ktor Resources.
 *
 * Instructions are personal library content, so every operation is scoped to the authenticated user
 * and the [InstructionService] verifies ownership before reading, writing or deleting. A foreign or
 * nonexistent instruction collapses to the same not-found error, so no existence leak exists. Writing
 * content here is what makes a row shareable: an agent role links rows by id, so an edit reaches every
 * role that links the row at the next read, while deleting a row is refused until no role links it.
 *
 * Available endpoints:
 * - GET /api/v1/instructions - List the requesting user's instructions (id ascending; every row names
 *   the roles that link it, so a client narrows the listing itself)
 * - GET /api/v1/instructions/{instructionId} - Read one instruction with the roles that link it
 * - POST /api/v1/instructions - Create an instruction owned by the requesting user
 * - PUT /api/v1/instructions - Replace an instruction's content (full replacement; the row is named
 *   by the request body's id)
 * - DELETE /api/v1/instructions/{instructionId} - Delete an instruction (204); refused with 409
 *   `resource-in-use` while any agent role still links the row, naming the roles to unlink first
 *
 * @param instructionService Service backing the instruction library operations.
 * @param authorizationService Authorization service retained for parity with the other resource routes;
 *            ownership enforcement is delegated to [InstructionService].
 */
fun Route.configureInstructionRoutes(
    instructionService: InstructionService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/instructions - List the user's library, including rows no role links any more
        get<InstructionResource> {
            val userId = call.getUserId()

            call.respond(instructionService.getAllInstructionsForUser(userId))
        }

        // GET /api/v1/instructions/{instructionId} - Read one library row with its usage (ownership checked)
        get<InstructionResource.ById> { resource ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: GetInstructionError -> e.toApiError() }) {
                    instructionService.getInstructionById(userId, resource.instructionId).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/instructions - Create a new instruction owned by the requesting user
        post<InstructionResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateInstructionRequest>()

            val result = either {
                withError({ e: CreateInstructionError -> e.toApiError() }) {
                    instructionService.createInstruction(userId, request).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // PUT /api/v1/instructions - Replace the instruction's content (ownership checked; the row is
        // shared, so every agent role linking it observes the change)
        put<InstructionResource> {
            val userId = call.getUserId()
            val request = call.receive<UpdateInstructionRequest>()

            val result = either {
                withError({ e: UpdateInstructionError -> e.toApiError() }) {
                    instructionService.updateInstruction(userId, request).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/instructions/{instructionId} - Delete the row (ownership checked) once no
        // agent role links it any more; a still-linked row yields 409 naming the roles to unlink first
        delete<InstructionResource.ById> { resource ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: DeleteInstructionError -> e.toApiError() }) {
                    instructionService.deleteInstruction(userId, resource.instructionId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}
