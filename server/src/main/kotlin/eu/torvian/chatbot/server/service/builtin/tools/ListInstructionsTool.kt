package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.encodeJsonElement
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseOptionalLong
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.InstructionService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list_instructions` server built-in tool.
 *
 * Reports the caller's instruction library as a JSON array of lean rows: each element carries only
 * the row id, its kind, its name and the ids of the roles that link it. Instruction text is what
 * makes a library row large, so the field set is this tool's size bound and the text is fetched one
 * row at a time with `read_instruction`.
 *
 * The optional `role_id` narrows the listing to one role's assigned instructions. That role is
 * ownership-checked first, so an unknown or foreign id fails with
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] instead of returning an empty array:
 * "this role has no instructions" and "there is no such role" must not read alike.
 *
 * @property instructionService User-scoped service providing the caller's library rows.
 * @property agentRoleService User-scoped role service resolving the optional `role_id` filter.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class ListInstructionsTool(
    private val instructionService: InstructionService,
    private val agentRoleService: AgentRoleService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.LIST_INSTRUCTIONS_NAME

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
        addUnknownParameterErrors(input, setOf(ServerBuiltInToolCatalog.ROLE_ID_PROPERTY), validationErrors)
        val roleId = parseOptionalLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // The filter role is resolved before the library is read: an unknown or foreign id is a
        // failure, never an empty listing, so the caller cannot confuse it with a role that simply
        // has no instructions.
        if (roleId != null) {
            agentRoleService.getRoleById(context.userId, roleId)
                .mapLeft {
                    ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                        "Agent role $roleId not found or not accessible by the current user."
                    )
                }
                .bind()
        }

        val rows = instructionService.getAllInstructionsForUser(context.userId)
            .filter { roleId == null || roleId in it.linkedRoleIds }
        val listing = buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonObject {
                        // The projection is the size bound of this tool: no message, no custom data
                        // and no derived summary.
                        put("id", row.id)
                        put("type", row.type)
                        put("name", row.name)
                        put("linkedRoleIds", buildJsonArray {
                            // Ascending ids keep the payload stable; the field itself is a set.
                            row.linkedRoleIds.sorted().forEach { add(it) }
                        })
                    }
                )
            }
        }
        encodeJsonElement(json, listing).bind()
    }
}
