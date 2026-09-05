package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Canonical, server-agnostic catalog of operator-executed tools.
 *
 * This is the single source of truth for the public metadata (name, description, and input JSON
 * Schema) of every operator tool. The server seeds **one `tool_definitions` row per user** from
 * these specs (see `OperatorToolDefinitionSeeder`), and the client's approval UI uses the same
 * catalog to recognize operator tools.
 *
 * The catalog describes the **kind** (`spawn_agent`): name, description, and input schema. It is
 * deliberately not a row — the seeder instantiates per-user instances from a spec. The name of an
 * operator tool doubles as the discriminator carried inside
 * `OperatorToolExecutionRequested.toolName`, letting the operator pick the correct payload
 * deserializer for a relayed execution request. Because operator tools are per-user instances, the
 * tool name is unique within the user's tool set.
 */
object OperatorToolCatalog {

    /** Public, LLM-facing name of the `spawn_agent` operator tool. */
    const val SPAWN_AGENT_NAME = "spawn_agent"

    /** JSON property holding the agent-role name for a spawn request (snake_case, see report §5.1.6). */
    const val SPAWN_AGENT_ROLE_NAME_PROPERTY = "agent_role_name"

    /** JSON property holding the user-facing subject for the spawned session. */
    const val SPAWN_AGENT_SUBJECT_PROPERTY = "subject"

    /** JSON property holding the prompt for a spawn request. */
    const val SPAWN_AGENT_PROMPT_PROPERTY = "prompt"

    /**
     * JSON property selecting interactive (handoff) spawn mode. Absent or `false` preserves the
     * default summary-return behavior (the tool returns the spawned agent's final summary); `true`
     * runs the spawn in handoff mode, where the tool completes with empty output after the first
     * spawned turn and the user continues the conversation in the app, reporting the result back to
     * the calling model manually.
     */
    const val SPAWN_AGENT_INTERACTIVE_PROPERTY = "interactive"

    /**
     * Immutable specification of a single operator tool.
     *
     * @property name Public tool name exposed to the LLM; also the discriminator carried in
     *            `OperatorToolExecutionRequested.toolName`.
     * @property description Human-readable description surfaced to the LLM.
     * @property inputSchema JSON Schema describing the tool's expected input arguments.
     */
    data class OperatorToolSpec(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    /**
     * All operator tool specifications, in stable catalog order.
     */
    val allTools: List<OperatorToolSpec> = listOf(
        OperatorToolSpec(
            name = SPAWN_AGENT_NAME,
            description = "Spawns a new agent conversation from a user-defined agent role and returns the spawned agent's final summary.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(SPAWN_AGENT_SUBJECT_PROPERTY, buildJsonObject {
                        put("type", "string")
                        put("description", "Subject used to name the spawned session. The client adds a spawned-session prefix.")
                    })
                    put(SPAWN_AGENT_ROLE_NAME_PROPERTY, buildJsonObject {
                        put("type", "string")
                        put("description", "Name of the agent role to spawn. The role must be owned by the current user.")
                    })
                    put(SPAWN_AGENT_PROMPT_PROPERTY, buildJsonObject {
                        put("type", "string")
                        put("description", "Task description for the spawned agent. The spawned agent is expected to end with a summary report.")
                    })
                    // Optional handoff switch: deliberately NOT in `required` so default-mode calls
                    // keep today's summary-return contract byte-for-byte and LLM-facing drift is
                    // confined to this property's description.
                    put(SPAWN_AGENT_INTERACTIVE_PROPERTY, buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Handoff mode. When true, the spawned conversation's first turn starts automatically but its summary is NOT returned to the calling model: the tool completes with empty output, and the user continues the conversation in the app and reports the result back manually. When false or absent (default), the tool returns the spawned agent's final summary as its result."
                        )
                    })
                })
                put("required", buildJsonArray {
                    add(SPAWN_AGENT_SUBJECT_PROPERTY)
                    add(SPAWN_AGENT_ROLE_NAME_PROPERTY)
                    add(SPAWN_AGENT_PROMPT_PROPERTY)
                })
            }
        )
    )
}
