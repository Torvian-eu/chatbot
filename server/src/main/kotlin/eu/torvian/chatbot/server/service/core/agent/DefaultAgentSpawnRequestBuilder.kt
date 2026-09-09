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
 * Parses the tool-call input JSON for the `subject`, `agent_role_name`, `prompt`, and optional
 * `mode` parameters (see [OperatorToolCatalog]), resolves the role by name through the
 * user-scoped [AgentRoleService.getRoleByName], enforces the source role's spawn allow-list through
 * [AgentRoleService.getRoleById], and assembles the [AgentSpawnRequest] with a single
 * [AgentSpawnMessage.User] carrying the prompt. The persisted [ToolCall.id] is used as the
 * correlation key echoed back in the operator's `ToolExecutionResult`.
 *
 * The optional `mode` enum is validated as pure tool input before any role lookup: absent →
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
 * The built request also carries the project the spawned session must be scoped to before the role
 * is attached — the calling session's project. Spawns are strictly same-scope: a project-attached
 * session may only spawn roles within that project (the request's `projectId` carries it), a
 * project-less session only unassociated roles (`projectId` = null). A role that does not belong to
 * the session's scope is rejected with [SpawnRequestBuildError.RoleNotInProject] instead of the
 * builder guessing a different project, because attaching such a role would violate the Session
 * Legality Invariant.
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
     * The optional [OperatorToolCatalog.SPAWN_AGENT_MODE_PROPERTY] enum is read as part of argument
     * validation, before any role lookup: absent → [OperatorToolMode.WAIT_FOR_RESPONSE]; a present
     * value must be a JSON string equal to one of the two serialized wire values, anything else
     * fails with [SpawnRequestBuildError.InvalidInput] and never reaches role resolution (malformed
     * tool input must not touch I/O or leak whether a role exists). A legacy `interactive` key is
     * ignored by property-name lookup and therefore falls back to wait mode.
     *
     * @param context Caller identity plus the turn's session/role/project context; see
     *            [ToolCallExecutionContext]. The project scope of the lookup comes from here.
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

            val roleName = arguments[OperatorToolCatalog.SPAWN_AGENT_ROLE_NAME_PROPERTY]
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: raise(
                    SpawnRequestBuildError.InvalidInput(
                        "Missing or blank '${OperatorToolCatalog.SPAWN_AGENT_ROLE_NAME_PROPERTY}' in spawn_agent arguments"
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
                    ?.let { raw -> runCatching { json.decodeFromString(OperatorToolMode.serializer(), "\"$raw\"") }.getOrNull() }
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

            // The lookup is user-scoped (names are only unique per owner within one scope), so a
            // NotFoundByName result means the role does not exist in the requested scope or belongs
            // to another user — both are reported identically. The scope follows the session: a
            // project-attached session resolves only roles within that project (context.projectId),
            // an unassociated session only unassociated roles (null). Using the session scope here
            // is what makes spawn-by-name work when a project is selected — resolving with a fixed
            // null would always miss project-associated roles.
            val role = withError({ _: AgentRoleError.NotFoundByName ->
                SpawnRequestBuildError.RoleNotFound(roleName)
            }) {
                agentRoleService.getRoleByName(context.userId, roleName, context.projectId).bind()
            }

            // The source role comes from the validated session, never from model-controlled arguments.
            // A missing/unauthorized source is reported like a missing target so the builder never
            // leaks whether the role exists.
            val sourceRole = withError({ _: AgentRoleError.NotFound ->
                SpawnRequestBuildError.RoleNotAllowed(roleName)
            }) {
                agentRoleService.getRoleById(context.userId, context.agentRoleId).bind()
            }
            ensure(role.id in sourceRole.spawnableAgentRoleIds) {
                SpawnRequestBuildError.RoleNotAllowed(roleName)
            }

            // Spawns are strictly same-scope: a project-attached session may only spawn roles within
            // that project, a project-less session only unassociated roles. The scoped name lookup
            // above already guarantees this, but enforce it explicitly so a role that ever escapes
            // the lookup still cannot be spawned outside its own scope — the builder never falls
            // back to a different project of the role (Session Legality Invariant). Roles have
            // single-project membership, so the check is an exact comparison.
            val projectScopeLegal = when (context.projectId) {
                null -> role.projectId == null
                else -> role.projectId == context.projectId
            }
            ensure(projectScopeLegal) {
                SpawnRequestBuildError.RoleNotInProject(roleName, context.projectId)
            }

            AgentSpawnRequest(
                agentRoleToSpawn = role,
                subject = subject,
                mode = mode,
                operatorType = OperatorType.CLIENT_APP,
                projectId = context.projectId,
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