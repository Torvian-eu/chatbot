package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for user-owned instruction endpoints.
 *
 * Instructions are authored here rather than through an agent-role payload, which references them by
 * id only. This resource defines the URL structure for the instruction library:
 * - GET /api/v1/instructions - List the instructions owned by the requesting user
 * - POST /api/v1/instructions - Create an instruction owned by the requesting user
 * - PUT /api/v1/instructions - Replace the content of an existing instruction (the change reaches
 *   every agent role that links the row)
 * - GET /api/v1/instructions/{instructionId} - Read one instruction with the roles that link it
 * - DELETE /api/v1/instructions/{instructionId} - Delete an instruction and unlink it everywhere
 *
 * The authoring endpoints address the row through the request body rather than the URL, because the
 * row identity travels with the authored content and a path segment would only repeat it. Addressing
 * an existing row by URL is what the read and delete operations need.
 *
 * There is no filter parameter on the listing: every reported row already names the roles that link
 * it, so a consumer narrows the delivered rows itself instead of asking the server for a selection it
 * would have to re-read.
 */
@Resource("instructions")
class InstructionResource(val parent: Api = Api()) {

    /**
     * Resource for operations on a specific instruction by ID.
     *
     * @property parent The parent [InstructionResource].
     * @property instructionId The unique identifier of the instruction.
     */
    @Resource("{instructionId}")
    class ById(val parent: InstructionResource = InstructionResource(), val instructionId: Long)
}
