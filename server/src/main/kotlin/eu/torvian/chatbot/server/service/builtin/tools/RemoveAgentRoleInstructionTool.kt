package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.JsonObject

/**
 * `remove_agent_role_instruction` server built-in tool.
 *
 * Removes the instruction at the given zero-based position of a role's instruction list without
 * rewriting the other instructions. The role is loaded via the ownership-checked lookup, the
 * instruction at `position` is dropped, and the merged state is applied through the existing
 * full-replacement role update, so every other role field is preserved unchanged.
 *
 * Returns a concise one-line summary of the completed operation (see [formatRemovedInstruction])
 * instead of the full role JSON to keep the LLM context lean; `read_agent_role` returns the full role.
 *
 * @property agentRoleService User-scoped role service used for the ownership-checked load and update.
 */
class RemoveAgentRoleInstructionTool(
    private val agentRoleService: AgentRoleService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.REMOVE_AGENT_ROLE_INSTRUCTION_NAME

    /** Catalog spec for this tool: the single source of [name], [description], and [inputSchema]. */
    private val spec: ServerBuiltInToolCatalog.ServerBuiltInToolSpec =
        requireNotNull(ServerBuiltInToolCatalog.specFor(name)) {
            "Catalog must contain a spec for server built-in tool '$name'"
        }

    override val description: String get() = spec.description
    override val inputSchema: JsonObject get() = spec.inputSchema

    override suspend fun execute(
        userId: Long,
        input: JsonObject
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(
                ServerBuiltInToolCatalog.ROLE_ID_PROPERTY,
                ServerBuiltInToolCatalog.POSITION_PROPERTY
            ),
            validationErrors
        )
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        val position = parseRequiredLong(input, ServerBuiltInToolCatalog.POSITION_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // roleId/position are non-null here: a null result always coincides with a recorded
        // validation error, and we bail out above when any error was recorded.
        val persisted = agentRoleService.getRoleById(userId, roleId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role $roleId not found or not accessible by the current user."
                )
            }
            .bind()

        // The removal position must address an existing list entry; an empty list has no valid
        // position at all, so the generic bounds message would otherwise read "between 0 and -1".
        val size = persisted.instructions.size
        val positionValue = position!!
        ensure(size != 0) {
            ServerBuiltInToolHandlerError.InvalidInput(
                "Agent role $roleId has no instructions to remove"
            )
        }
        if (positionValue !in 0..<size) {
            raise(
                ServerBuiltInToolHandlerError.InvalidInput(
                    "Argument 'position' must be between 0 and ${size - 1} (inclusive) for a role " +
                        "with $size instruction(s)"
                )
            )
        }

        val removed = persisted.instructions[positionValue.toInt()]
        val newInstructions = persisted.instructions.toMutableList().apply {
            removeAt(positionValue.toInt())
        }

        val request = UpdateAgentRoleRequest(
            name = persisted.name,
            displayName = persisted.displayName,
            description = persisted.description,
            modelId = persisted.modelId,
            modelSettingsId = persisted.modelSettingsId,
            toolIds = persisted.tools,
            spawnableAgentRoleIds = persisted.spawnableAgentRoleIds,
            instructions = newInstructions
        )

        val role = agentRoleService.updateRole(userId, roleId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        formatRemovedInstruction(role, positionValue.toInt(), removed)
    }
}
