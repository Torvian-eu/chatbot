package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.OperatorType
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import kotlinx.serialization.json.*

/**
 * Default implementation of [AgentSpawnRequestBuilder].
 *
 * Parses the tool-call input JSON for the `subject`, `agent_role_id`, `prompt`, and optional `mode`
 * parameters (see [OperatorToolCatalog]), resolves the target by its owner-scoped id
 * ([AgentRoleService.getRoleById]), enforces the source role's spawn allow-list through the same
 * lookup, and assembles the [AgentSpawnRequest] with a single [AgentSpawnMessage.User] carrying the
 * prompt. The persisted [ToolCall.id] is used as the correlation key echoed back in the operator's
 * `ToolExecutionResult`.
 *
 * The required `agent_role_id` is read like the server built-in convention (`parseRequiredLong`): a
 * JSON integer or a numeric JSON string is accepted, everything else (absent key, blank or
 * non-numeric string, fraction, boolean, explicit null, array, object) is malformed tool input and
 * fails with [SpawnRequestBuildError.InvalidInput] **before any role read**, so a bad argument can
 * never leak whether a role exists. A call that addresses the target by name instead of id — one
 * produced by a per-user tool schema that predates the id parameter, or a pending call from before
 * that schema was reset — carries no id key and fails the same way, and the message points at the
 * available-agents table so the model can re-issue with an id.
 *
 * The optional `mode` enum is validated as pure tool input too: absent →
 * [OperatorToolMode.WAIT_FOR_RESPONSE] (default summary-return mode); present must be a JSON string
 * equal to one of the two serialized wire values (`wait_for_response` / `fire_and_forget`), any
 * other JSON value → [SpawnRequestBuildError.InvalidInput]. The mode is carried into the request
 * unchanged and selects whether the operator returns the spawned summary (wait) or starts the
 * spawned turn in the background and returns only the session id (fire-and-forget).
 *
 * @property agentRoleService User-scoped agent-role lookup used to resolve the spawn target and the
 *            source role's allow-list.
 * @property json JSON codec used to decode the tool-call arguments.
 *
 * The built request carries the project the spawned session must be scoped to before the role is
 * attached — the **target role's** project, or `null` when the target is unassociated. The target may
 * live in any project scope, including one other than the calling session's: the source role's spawn
 * allow-list is what authorises the spawn. An unknown or foreign id fails with
 * [SpawnRequestBuildError.RoleNotFound], an owned id outside the allow-list (or an unloadable source
 * role) with [SpawnRequestBuildError.RoleNotAllowed] — there is no name lookup and no fallback to a
 * different role or project.
 */
class DefaultAgentSpawnRequestBuilder(
    private val agentRoleService: AgentRoleService,
    private val json: Json
) : AgentSpawnRequestBuilder {

    override suspend fun build(
        context: ToolCallExecutionContext,
        toolCall: ToolCall
    ): Either<SpawnRequestBuildError, AgentSpawnRequest> =
        buildInternal(context, toolCall)

    /**
     * Parses and resolves a spawn call and applies source-role authorization.
     *
     * The required [OperatorToolCatalog.SPAWN_AGENT_ROLE_ID_PROPERTY] and the optional
     * [OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY] are read as part of argument validation, before
     * any role read: a missing or non-integer id → [SpawnRequestBuildError.InvalidInput]; absent mode →
     * [OperatorToolMode.WAIT_FOR_RESPONSE], a present mode must be a JSON string equal to one of the two
     * serialized wire values, anything else → [SpawnRequestBuildError.InvalidInput]. Malformed tool input
     * must not touch I/O or leak whether a role exists. Unknown argument keys are ignored, so a payload
     * that carries both the id and a stale name key succeeds and a name-only payload fails for the
     * missing id.
     *
     * @param context Caller identity plus the turn's session/role context; see
     *            [ToolCallExecutionContext]. Only the caller and the source role are read from here —
     *            the session's project scope plays no role in target resolution.
     * @param toolCall Persisted call to parse.
     * @return Validated spawn payload or a logical build failure.
     */
    private suspend fun buildInternal(
        context: ToolCallExecutionContext,
        toolCall: ToolCall
    ): Either<SpawnRequestBuildError, AgentSpawnRequest> = either {
        val arguments = parseArguments(toolCall.input).bind()

        // Tool arguments are untrusted JSON; safe casts keep arrays and objects in the typed error path.
        val subject = arguments[OperatorToolCatalog.SPAWN_AGENT_SUBJECT_PROPERTY]
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: raise(
                SpawnRequestBuildError.InvalidInput(
                    "Missing or blank '${OperatorToolCatalog.SPAWN_AGENT_SUBJECT_PROPERTY}' in spawn_agent arguments"
                )
            )

        // Required target role id: the id is the allow-list entry, so a missing or non-integer value is
        // malformed tool input and never reaches I/O. Mirrors the server built-in convention
        // (`parseRequiredLong`): a JSON number or a numeric JSON string is accepted, everything else is
        // rejected. The message points at the available-agents table, the only place the ids are
        // advertised — which is also what guides a model whose persisted schema still says
        // `agent_role_name`.
        val roleId = arguments[OperatorToolCatalog.SPAWN_AGENT_ROLE_ID_PROPERTY]
            ?.let { it as? JsonPrimitive }
            ?.longOrNull
            ?: raise(
                SpawnRequestBuildError.InvalidInput(
                    "Missing or non-integer '${OperatorToolCatalog.SPAWN_AGENT_ROLE_ID_PROPERTY}' in " +
                        "spawn_agent arguments; pass the role id shown in the available agents table"
                )
            )

        val prompt = arguments[OperatorToolCatalog.SPAWN_AGENT_PROMPT_PROPERTY]
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: raise(
                SpawnRequestBuildError.InvalidInput(
                    "Missing or blank '${OperatorToolCatalog.SPAWN_AGENT_PROMPT_PROPERTY}' in spawn_agent arguments"
                )
            )

        // Optional execution mode: absent → wait-for-response (default). A present value must be
        // a JSON string equal to one of the two serialized wire values; strings/numbers/objects/
        // arrays/explicit null are malformed tool input, reported as InvalidInput exactly like
        // the other parameter checks above. JSON `null` is a JsonPrimitive whose contentOrNull is
        // null, so it also lands here instead of silently falling back to default mode. The
        // enum's @SerialName values are the schema values too, so decoding through the serializer
        // keeps both aligned.
        val mode = when (val element = arguments[OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY]) {
            null -> OperatorToolMode.WAIT_FOR_RESPONSE
            is JsonPrimitive -> element.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching {
                        json.decodeFromString(
                            OperatorToolMode.serializer(),
                            "\"$raw\""
                        )
                    }.getOrNull()
                }
                ?: raise(
                    SpawnRequestBuildError.InvalidInput(
                        "'${OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY}' must be one of 'wait_for_response', 'fire_and_forget' in spawn_agent arguments"
                    )
                )

            else -> raise(
                SpawnRequestBuildError.InvalidInput(
                    "'${OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY}' must be one of 'wait_for_response', 'fire_and_forget' in spawn_agent arguments"
                )
            )
        }

        // Owner-scoped by construction: a foreign or non-existent id collapses to the same not-found
        // error, so the builder never leaks whether somebody else's role exists. No project scope is
        // consulted: the source role's allow-list alone decides what may be spawned.
        val target = withError({ _: AgentRoleError.NotFound ->
            SpawnRequestBuildError.RoleNotFound(roleId)
        }) {
            agentRoleService.getRoleById(context.userId, roleId).bind()
        }

        // The source role comes from the validated session, never from model-controlled arguments.
        // A missing/unauthorized source is reported like an ungranted target so the builder never
        // leaks whether the role exists.
        val sourceRole = withError({ _: AgentRoleError.NotFound ->
            SpawnRequestBuildError.RoleNotAllowed(roleId)
        }) {
            agentRoleService.getRoleById(context.userId, context.agentRoleId).bind()
        }

        // The allow-list is the whole authorization rule; the target's project scope is deliberately not
        // compared. Self-spawn keeps working because a role may grant its own id.
        ensure(target.id in sourceRole.spawnableAgentRoleIds) { SpawnRequestBuildError.RoleNotAllowed(roleId) }

        AgentSpawnRequest(
            agentRoleToSpawn = target,
            subject = subject,
            mode = mode,
            operatorType = OperatorType.CLIENT_APP,
            // The spawned session must be scoped to the target role's own project (null for an
            // unassociated target) so attaching the role never violates the Session Legality
            // Invariant.
            projectId = target.projectId,
            conversation = listOf(AgentSpawnMessage.User(prompt)),
            toolCallId = toolCall.id
        )
    }

    /**
     * Decodes the tool-call input JSON into a [JsonObject].
     *
     * A missing or non-object input is a caller error; a malformed input is reported as
     * [SpawnRequestBuildError.InvalidInput] so the LLM sees a readable tool error rather than a crash.
     *
     * @param input Raw arguments string from the persisted tool call.
     * @return Either the parsed arguments object or an [SpawnRequestBuildError.InvalidInput].
     */
    private fun parseArguments(input: String?): Either<SpawnRequestBuildError, JsonObject> = either {
        ensure(!input.isNullOrBlank()) { SpawnRequestBuildError.InvalidInput("spawn_agent arguments are empty") }
        val element = runCatching { json.parseToJsonElement(input) }.getOrElse { error ->
            raise(SpawnRequestBuildError.InvalidInput("Failed to parse spawn_agent arguments: ${error.message}"))
        }
        ensure(element is JsonObject) {
            SpawnRequestBuildError.InvalidInput("spawn_agent arguments must be a JSON object")
        }
        element.jsonObject
    }
}