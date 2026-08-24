package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition that is executed entirely in-process on the server.
 *
 * Server built-in tools (e.g. `list_agent_roles`, `update_agent_role`) are cataloged in
 * [ServerBuiltInToolCatalog] and seeded as **per-user instances**: the base `tool_definitions` row
 * is linked to exactly one [userId] via the `server_builtin_tool_definitions` side table, mirroring
 * the operator-tool pattern. This keeps approval preferences
 * (`user_tool_approval_preferences` composite key `(userId, toolDefinitionId)`) and the per-user
 * enable/disable flag naturally scoped, and lets `ToolDefinitionDao` ownership filtering treat
 * server built-in tools like any other user-owned tool.
 *
 * The public [name] is prefix-derived and seeder-owned: it is the canonical catalog name with the
 * user's configured tool-name prefix prepended (e.g. `chatbot-list_agent_roles`), so it may differ
 * per user and can change when the user changes their prefix. The immutable [builtInToolName] is
 * the canonical, unprefixed catalog name and is the value used for deduplication, reconciliation,
 * and in-process execution dispatch — never the public [name].
 *
 * @property id Unique identifier for this tool definition.
 * @property name Machine-readable public tool name (used in LLM API calls). NOT globally unique;
 *            unique per user; equals the user's prefix (if any) plus the canonical
 *            [ServerBuiltInToolCatalog] name.
 * @property description Human-readable explanation of the tool's purpose.
 * @property config Tool-specific configuration (JSON object).
 * @property inputSchema JSON Schema defining expected input parameters.
 * @property outputSchema Optional JSON Schema defining expected output structure.
 * @property isEnabled Whether this tool is available for the owning user.
 * @property createdAt Timestamp when the tool was created.
 * @property updatedAt Timestamp when the tool was last modified.
 * @property userId Owning user; each user has their own instance of this server built-in tool.
 * @property builtInToolName Canonical, unprefixed catalog name (e.g. `list_agent_roles`); immutable
 *            and used as the executor dispatch key.
 */
@Serializable
data class ServerBuiltInToolDefinition(
    override val id: Long,
    override val name: String,
    override val description: String,
    override val config: JsonObject,
    override val inputSchema: JsonObject,
    override val outputSchema: JsonObject? = null,
    override val isEnabled: Boolean,
    override val createdAt: Instant,
    override val updatedAt: Instant,
    val userId: Long,
    val builtInToolName: String,
) : ToolDefinition() {
    @SerialName("tool_type") // 'type' is a reserved property used by serialization
    override val type: ToolType = ToolType.BUILTIN_SERVER

    override fun withUpdatedAt(newUpdatedAt: Instant): ToolDefinition {
        return this.copy(updatedAt = newUpdatedAt)
    }
}
