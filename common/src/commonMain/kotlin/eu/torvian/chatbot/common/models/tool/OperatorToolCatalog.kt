package eu.torvian.chatbot.common.models.tool

import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import kotlinx.serialization.descriptors.elementNames
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
 * The catalog describes the **kinds** of operator tools (`spawn_agent`, `send_message`): name,
 * description, and input schema. It is deliberately not a row — the seeder instantiates per-user
 * instances from a spec. The name of an operator tool doubles as the discriminator carried inside
 * `OperatorToolExecutionRequested.toolName`, letting the operator pick the correct payload
 * deserializer for a relayed execution request. Because operator tools are per-user instances, the
 * tool name is unique within the user's tool set.
 *
 * The order of [allTools] is a documented contract: `spawn_agent` first, `send_message` second.
 * Seeding and reset-to-defaults iterate the catalog, and the client's approval UI list is derived
 * from these specs, so appending a new spec at the end automatically provisions it for every user
 * without a DB migration.
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
     * JSON property selecting the spawn execution mode. Absent → wait-for-response (default). The
     * schema's `enum` values are derived from [OperatorToolMode]'s `@SerialName` values via
     * [operatorToolModeWireValues] — the only place the wire literals are written out — so the
     * LLM-facing schema and the relay DTO wire contract can never drift; both modes return the
     * spawned chat session id.
     */
    const val SPAWN_AGENT_MODE_PROPERTY = "mode"

    /** Public, LLM-facing name of the `send_message` operator tool. */
    const val SEND_MESSAGE_NAME = "send_message"

    /** JSON property holding the target chat-session id for a `send_message` request. */
    const val SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY = "chat_session_id"

    /** JSON property holding the message text for a `send_message` request. */
    const val SEND_MESSAGE_MESSAGE_PROPERTY = "message"

    /**
     * JSON property selecting the shared operator-tool execution mode for `send_message`. Identical
     * wire values to [SPAWN_AGENT_MODE_PROPERTY], both derived from [OperatorToolMode] via
     * [operatorToolModeWireValues]; absent → wait-for-response (default).
     */
    const val SEND_MESSAGE_MODE_PROPERTY = "mode"

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
     *
     * Order is a documented contract: `spawn_agent` first, `send_message` second. Both tools share
     * the `mode` parameter with the wire values `wait_for_response` / `fire_and_forget`, and both
     * return plain-text results; `spawn_agent` additionally returns the spawned chat session id so
     * the caller can later reach the spawned agent via `send_message`.
     */
    val allTools: List<OperatorToolSpec> = listOf(
        OperatorToolSpec(
            name = SPAWN_AGENT_NAME,
            description = "Spawns a new agent conversation from a user-defined agent role. Both modes return the spawned chat session id; wait mode also returns the spawned agent's final summary, fire-and-forget mode returns immediately while the spawned conversation runs in the background.",
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
                    // Optional mode switch: deliberately NOT in `required` so default-mode calls
                    // keep today's summary-return contract byte-for-byte and LLM-facing drift is
                    // confined to this property's description.
                    put(SPAWN_AGENT_MODE_PROPERTY, buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            operatorToolModeWireValues().forEach { add(it) }
                        })
                        put(
                            "description",
                            "Execution mode. `wait_for_response` (default): the tool waits for the spawned conversation and returns its final summary, prefixed with the spawned chat session id. `fire_and_forget`: the spawned conversation starts in the background and the tool returns only the spawned chat session id immediately."
                        )
                    })
                })
                put("required", buildJsonArray {
                    add(SPAWN_AGENT_SUBJECT_PROPERTY)
                    add(SPAWN_AGENT_ROLE_NAME_PROPERTY)
                    add(SPAWN_AGENT_PROMPT_PROPERTY)
                })
            }
        ),
        OperatorToolSpec(
            name = SEND_MESSAGE_NAME,
            description = "Sends a message into another chat session owned by the current user (cross-session communication between agents). `wait_for_response` returns the target session's last assistant message; `fire_and_forget` returns a success notification immediately while the target turn runs in the background.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put(SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY, buildJsonObject {
                        put("type", "integer")
                        put("description", "Session id of the chat session to send the message to. The session must be owned by the current user.")
                    })
                    put(SEND_MESSAGE_MESSAGE_PROPERTY, buildJsonObject {
                        put("type", "string")
                        put("description", "Message text to inject as a user message into the target session.")
                    })
                    // Optional mode switch; same wire values and default as the spawn tool, derived
                    // from the same single source of truth as the spawn schema.
                    put(SEND_MESSAGE_MODE_PROPERTY, buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            operatorToolModeWireValues().forEach { add(it) }
                        })
                        put(
                            "description",
                            "Execution mode. `wait_for_response` (default): the tool waits for the target turn and returns its last assistant message, prefixed with the target chat session id. `fire_and_forget`: the message is injected and the target turn starts in the background; the tool returns only a success notification."
                        )
                    })
                })
                put("required", buildJsonArray {
                    add(SEND_MESSAGE_CHAT_SESSION_ID_PROPERTY)
                    add(SEND_MESSAGE_MESSAGE_PROPERTY)
                })
            }
        )
    )

    /**
     * Serialized wire values of [OperatorToolMode] in declaration order.
     *
     * The enum's serializer descriptor names each entry with its `@SerialName` value (falling back
     * to the Kotlin property name when no `@SerialName` is present), so this is the single source
     * of truth for the LLM-facing schema `enum` arrays of both tools. Keeping the values derived
     * here (rather than re-written as string literals in each property schema) guarantees the
     * catalog schema can never drift from the relay DTO wire contract, and the resulting schema
     * output stays byte-identical to the previously hand-written literals.
     */
    private fun operatorToolModeWireValues(): List<String> =
        OperatorToolMode.serializer().descriptor.elementNames.toList()
}