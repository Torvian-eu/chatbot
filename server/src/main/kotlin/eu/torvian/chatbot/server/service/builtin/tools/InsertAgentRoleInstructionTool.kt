package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseRequiredInstruction
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.JsonObject

/**
 * `insert_agent_role_instruction` server built-in tool.
 *
 * Inserts one instruction at the given zero-based position of a role's instruction list without
 * rewriting the other instructions. The role is loaded via the ownership-checked lookup, the new
 * instruction is spliced into the list at `position` (the current list size appends at the end),
 * and the merged state is applied through the existing full-replacement role update so the shared
 * instruction-list validation (at most one `role`/`main`/`spawnable_agents`, distinct
 * `model_specific` targets) still applies. Every other role field is preserved unchanged.
 *
 * Returns a concise one-line summary of the completed operation (see [formatInsertedInstruction])
 * instead of the full role JSON to keep the LLM context lean; `read_agent_role` returns the full role.
 *
 * @property agentRoleService User-scoped role service used for the ownership-checked load and update.
 */
class InsertAgentRoleInstructionTool(
    private val agentRoleService: AgentRoleService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.INSERT_AGENT_ROLE_INSTRUCTION_NAME

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
                ServerBuiltInToolCatalog.POSITION_PROPERTY,
                ServerBuiltInToolCatalog.INSTRUCTION_PROPERTY
            ),
            validationErrors
        )
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        val position = parseRequiredLong(input, ServerBuiltInToolCatalog.POSITION_PROPERTY, validationErrors)
        val instruction =
            parseRequiredInstruction(input, ServerBuiltInToolCatalog.INSTRUCTION_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // roleId/position/instruction are non-null here: a null result always coincides with a
        // recorded validation error, and we bail out above when any error was recorded.
        val persisted = agentRoleService.getRoleById(userId, roleId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role $roleId not found or not accessible by the current user."
                )
            }
            .bind()

        // The insert position is zero-based and may append at the end (position == size); anything
        // outside [0, size] cannot be expressed as a list index.
        val size = persisted.instructions.size
        val positionValue = position!!
        if (positionValue !in 0..size) {
            raise(
                ServerBuiltInToolHandlerError.InvalidInput(
                    "Argument 'position' must be between 0 and $size (inclusive) for a role " +
                        "with $size instruction(s)"
                )
            )
        }

        val newInstructions = persisted.instructions.toMutableList().apply {
            add(positionValue.toInt(), instruction!!)
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
        formatInsertedInstruction(role, positionValue.toInt(), instruction!!)
    }
}
