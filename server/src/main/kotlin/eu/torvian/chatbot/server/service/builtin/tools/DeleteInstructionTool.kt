package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.DeleteInstructionError
import kotlinx.serialization.json.JsonObject

/**
 * `delete_instruction` server built-in tool.
 *
 * Deletes the ownership-checked instruction row, which succeeds only while no agent role links it:
 * a linked row fails with `instruction_in_use` naming the roles to unlink first, so a successful
 * summary never has a role to report. The row is read before it is deleted to learn its name.
 *
 * @property instructionService User-scoped service providing the ownership-checked row and the
 *            delete.
 */
class DeleteInstructionTool(
    private val instructionService: InstructionService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.DELETE_INSTRUCTION_NAME

    /** Catalog spec for this tool: the single source of [name], [description], and [inputSchema]. */
    private val spec: ServerBuiltInToolCatalog.ServerBuiltInToolSpec =
        requireNotNull(ServerBuiltInToolCatalog.specFor(name)) {
            "Catalog must contain a spec for server built-in tool '$name'"
        }

    override val description: String get() = spec.description
    override val inputSchema: JsonObject get() = spec.inputSchema

    override suspend fun execute(
        input: JsonObject,
        context: ToolCallExecutionContext
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY),
            validationErrors
        )
        val instructionId =
            parseRequiredLong(input, ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }
        // instructionId is non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded.
        val row = instructionService.getInstructionById(context.userId, instructionId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Instruction $instructionId not found or not accessible by the current user."
                )
            }
            .bind()
        instructionService.deleteInstruction(context.userId, instructionId)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatDeletedInstruction(row)
    }
}

/**
 * Maps a [DeleteInstructionError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * Not-found/not-accessible covers a foreign and a nonexistent row alike, so existence never leaks.
 * A still-linked row is a recoverable failure that names the blocking role ids, because the caller
 * can unlink them and retry.
 *
 * @receiver The typed delete-instruction failure.
 * @return The corresponding handler error.
 */
private fun DeleteInstructionError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is DeleteInstructionError.NotFound ->
        ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
            "Instruction $instructionId not found or not accessible by the current user."
        )

    is DeleteInstructionError.LinkedToRoles ->
        ServerBuiltInToolHandlerError.OperationFailed(
            "instruction_in_use",
            "Instruction $instructionId is still linked to agent role(s) ${linkedRoleIds.joinToString(", ")}. " +
                "Unlink it from every role first, or delete those roles."
        )
}
