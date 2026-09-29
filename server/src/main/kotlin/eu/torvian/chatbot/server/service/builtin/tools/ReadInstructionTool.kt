package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.InstructionService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `read_instruction` server built-in tool.
 *
 * Returns the whole reported instruction row: its identity and label, the stored text and
 * kind-specific data it was read for, and the roles the row reaches. `list_instructions` stays lean
 * because this is the intended way to fetch instruction text.
 *
 * The row is encoded through the shared codec rather than assembled key by key, so the result is the
 * same shape every other surface reports. A missing or foreign row collapses into one
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible], so this tool never leaks the existence of
 * another user's instruction.
 *
 * @property instructionService User-scoped service providing the ownership-checked row.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ReadInstructionTool(
    private val instructionService: InstructionService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.READ_INSTRUCTION_NAME

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
        encodeResult(json, row).bind()
    }
}
