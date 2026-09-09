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
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `get_current_session_info` server built-in tool.
 *
 * Returns the identity of the turn's chat session (`session_id`, `session_name`) together with the
 * identity of the agent role selected for that session (`agent_role_id`, `agent_role_name`, and —
 * only when set and non-blank — `agent_role_display_name`) and the session's project scope
 * (`project_id`, emitted only when a project is selected). The session is the one the current
 * conversation belongs to and is strictly user-scoped, so no other user's data is ever surfaced.
 * The tool accepts no input parameters; any supplied argument is rejected as invalid input.
 *
 * The session context arrives via the fully-populated [ToolCallExecutionContext] produced by the
 * chat-turn pipeline: a validated session with a selected agent role is guaranteed before tool
 * calls execute. When the referenced role is missing or not accessible, the tool fails with a
 * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] instead of reporting incomplete role
 * identity — this tool is only meaningful inside a real session with a resolvable role.
 *
 * The output is assembled as a [JsonObject] so the optional `agent_role_display_name` and
 * `project_id` are structurally omitted (never emitted as an explicit `null`) regardless of the
 * shared codec's `encodeDefaults`/`explicitNulls` settings; `session_id`/`session_name` and
 * `agent_role_id`/`agent_role_name` are always present, and the deferred
 * `message_turn_count`/`token_count` keys are never produced.
 *
 * @property agentRoleService User-scoped role service used to resolve the session's selected role.
 * @property json Shared JSON codec used to serialize the handler output.
 */
class GetCurrentSessionInfoTool(
    private val agentRoleService: AgentRoleService,
    private val json: Json
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.GET_CURRENT_SESSION_INFO_NAME

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
        // Parameterless tool: reject any argument so hallucinated parameters surface to the LLM.
        addUnknownParameterErrors(input, emptySet(), validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // The turn pipeline guarantees a session with a selected role; if that role is missing or
        // not accessible, an explicit error is more truthful than role-less output for a tool whose
        // entire purpose is reporting the session's role identity.
        val role = agentRoleService.getRoleById(context.userId, context.agentRoleId)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role ${context.agentRoleId} not found or not accessible by the current user."
                )
            }
            .bind()

        val output = buildJsonObject {
            put("session_id", context.sessionId)
            put("session_name", context.sessionName)
            put("agent_role_id", role.id)
            put("agent_role_name", role.name)
            // The display name is optional: null or blank values are omitted so consumers can
            // always fall back to the machine-readable name, keeping the output token-lean.
            if (!role.displayName.isNullOrBlank()) {
                put("agent_role_display_name", role.displayName)
            }
            // The session's project scope: emitted only when a project is selected so consumers
            // can treat absence as "no project" (structural omission, mirroring the display name).
            val sessionProjectId = context.projectId
            if (sessionProjectId != null) {
                put("project_id", sessionProjectId)
            }
        }
        encodeJsonElement(json, output).bind()
    }
}
