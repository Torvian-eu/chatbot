package eu.torvian.chatbot.server.service.core.agent

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.agent.OperatorType
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import kotlinx.serialization.json.*

/**
 * Default implementation of [AgentSpawnRequestBuilder].
 *
 * Parses the tool-call input JSON for the `subject`, `agent_role_name`, and `prompt` parameters
 * (see [OperatorToolCatalog]), resolves the role by name through the user-scoped
 * [AgentRoleService.getRoleByName], enforces the source role's spawn allow-list through
 * [AgentRoleService.getRoleById], and assembles the [AgentSpawnRequest] with a single
 * [AgentSpawnMessage.User] carrying the prompt. The persisted [ToolCall.id] is used as the
 * correlation key echoed back in the operator's `ToolExecutionResult`.
 *
 * @property agentRoleService User-scoped agent-role lookup used to resolve the spawn target and the
 *            source role's allow-list.
 * @property json JSON codec used to decode the tool-call arguments.
 */
class DefaultAgentSpawnRequestBuilder(
    private val agentRoleService: AgentRoleService,
    private val json: Json
) : AgentSpawnRequestBuilder {

    override suspend fun build(
        userId: Long,
        requestingAgentRoleId: Long,
        toolCall: ToolCall
    ): Either<SpawnRequestBuildError, AgentSpawnRequest> =
        buildInternal(userId, requestingAgentRoleId, toolCall)

    /**
     * Parses and resolves a spawn call and applies source-role authorization.
     *
     * @param userId Ownership scope for role lookup.
     * @param requestingAgentRoleId Source role id from the validated session.
     * @param toolCall Persisted call to parse.
     * @return Validated spawn payload or a logical build failure.
     */
    private suspend fun buildInternal(
        userId: Long,
        requestingAgentRoleId: Long,
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

            // The lookup is user-scoped (names are only unique per owner), so a NotFoundByName result
            // means the role does not exist or belongs to another user — both are reported identically.
            val role = withError({ _: AgentRoleError.NotFoundByName ->
                SpawnRequestBuildError.RoleNotFound(roleName)
            }) {
                agentRoleService.getRoleByName(userId, roleName).bind()
            }

            // The source role comes from the validated session, never from model-controlled arguments.
            // A missing/unauthorized source is reported like a missing target so the builder never
            // leaks whether the role exists.
            val sourceRole = withError({ _: AgentRoleError.NotFound ->
                SpawnRequestBuildError.RoleNotAllowed(roleName)
            }) {
                agentRoleService.getRoleById(userId, requestingAgentRoleId).bind()
            }
            ensure(role.id in sourceRole.spawnableAgentRoleIds) {
                SpawnRequestBuildError.RoleNotAllowed(roleName)
            }

            AgentSpawnRequest(
                agentRoleToSpawn = role,
                subject = subject,
                operatorType = OperatorType.CLIENT_APP,
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
