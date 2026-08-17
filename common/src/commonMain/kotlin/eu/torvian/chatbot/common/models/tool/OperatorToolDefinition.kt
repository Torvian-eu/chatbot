package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition that is executed by the operator over the chat WebSocket.
 *
 * Operator tools (e.g. `spawn_agent`) are server-orchestrated: after approval the server relays a
 * tool-specific execution request to the operator (in v1 the client app itself), who runs the tool
 * and returns the result through the same chat socket. There is no worker dispatch and therefore no
 * on-device signature is required for the operator tool call itself.
 *
 * Each user gets their **own** instance of every operator tool: the base `tool_definitions` row is
 * linked to exactly one [userId] via the `operator_tool_definitions` side table. This keeps approval
 * preferences (`user_tool_approval_preferences` composite key `(userId, toolDefinitionId)`) and the
 * per-user enable/disable flag naturally scoped, and lets `ToolDefinitionDao` ownership filtering
 * treat operator tools like any other user-owned tool.
 *
 * @property id Unique identifier for this tool definition.
 * @property name Machine-readable tool name (used in LLM API calls). NOT globally unique.
 * @property description Human-readable explanation of the tool's purpose.
 * @property config Tool-specific configuration (JSON object).
 * @property inputSchema JSON Schema defining expected input parameters.
 * @property outputSchema Optional JSON Schema defining expected output structure.
 * @property isEnabled Whether this tool is available for the owning user.
 * @property createdAt Timestamp when the tool was created.
 * @property updatedAt Timestamp when the tool was last modified.
 * @property userId Owning user; each user has their own instance of this operator tool.
 */
@Serializable
data class OperatorToolDefinition(
    override val id: Long,
    override val name: String,
    override val description: String,
    override val config: JsonObject,
    override val inputSchema: JsonObject,
    override val outputSchema: JsonObject? = null,
    override val isEnabled: Boolean,
    override val createdAt: Instant,
    override val updatedAt: Instant,
    val userId: Long
) : ToolDefinition() {
    @SerialName("tool_type") // 'type' is a reserved property used by serialization
    override val type: ToolType = ToolType.OPERATOR

    override fun withUpdatedAt(newUpdatedAt: Instant): ToolDefinition {
        return this.copy(updatedAt = newUpdatedAt)
    }
}
