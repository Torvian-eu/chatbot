package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeResult
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseOptionalJsonObject
import eu.torvian.chatbot.server.service.builtin.parseOptionalString
import eu.torvian.chatbot.server.service.builtin.parseRequiredString
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.CreateInstructionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `create_instruction` server built-in tool.
 *
 * Creates an instruction row owned by the caller from the parsed input, reusing
 * [CreateInstructionRequest] so the authoring rules stay in one place. Content validation belongs to
 * the service (known kind, non-blank name of at most 255 characters, `model_specific` with a usable
 * `custom.modelId`, no message for the generated-message kind), and its failures are reported with
 * the service's reason instead of being duplicated here.
 *
 * The created row is echoed as full JSON because its server-generated id is what the caller needs to
 * assign it to a role in the same turn; a new row links no role, so its role list is empty.
 *
 * @property instructionService User-scoped service that validates and persists the new row.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class CreateInstructionTool(
    private val instructionService: InstructionService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.CREATE_INSTRUCTION_NAME

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
            setOf(
                ServerBuiltInToolCatalog.TYPE_PROPERTY,
                ServerBuiltInToolCatalog.NAME_PROPERTY,
                ServerBuiltInToolCatalog.MESSAGE_PROPERTY,
                ServerBuiltInToolCatalog.CUSTOM_PROPERTY
            ),
            validationErrors
        )
        val type = parseRequiredString(input, ServerBuiltInToolCatalog.TYPE_PROPERTY, validationErrors)
        val name = parseRequiredString(input, ServerBuiltInToolCatalog.NAME_PROPERTY, validationErrors)
        val message = parseOptionalString(input, ServerBuiltInToolCatalog.MESSAGE_PROPERTY, validationErrors)
        val custom = parseOptionalJsonObject(input, ServerBuiltInToolCatalog.CUSTOM_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // type and name are non-null here: a null result always coincides with a recorded validation
        // error, and we bail out above when any error was recorded.
        val request = CreateInstructionRequest(
            type = type!!,
            name = name!!,
            message = message,
            custom = custom
        )
        val created = instructionService.createInstruction(context.userId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        encodeResult(json, created).bind()
    }
}

/**
 * Maps a [CreateInstructionError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * The service's validation reason is forwarded verbatim, because it states the violated rule; the
 * ownership failure carries no rule for the caller to fix, so it is reported as a failed operation
 * (the transaction rolled the insert back, so nothing was persisted).
 *
 * @receiver The typed create-instruction failure.
 * @return The corresponding handler error.
 */
private fun CreateInstructionError.toHandlerError(): ServerBuiltInToolHandlerError = when (this) {
    is CreateInstructionError.ValidationFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("instruction_validation_failed", reason)

    is CreateInstructionError.OwnerInsertFailed ->
        ServerBuiltInToolHandlerError.OperationFailed("instruction_owner_insert_failed", reason)
}
