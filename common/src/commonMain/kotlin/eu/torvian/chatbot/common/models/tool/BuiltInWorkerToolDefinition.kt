package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition that is provided by a specific worker as a built-in tool.
 *
 * The worker resolves the public [name] (which includes any configured prefix) against its in-memory
 * built-in tool registry and executes the matching implementation inside its `workspace` directory.
 * The unprefixed implementation is identified by [builtInToolName].
 *
 * @property workerId Unique identifier for the worker that exposes this tool.
 * @property builtInToolName Unprefixed tool name used to look up the implementation on the worker.
 */
@Serializable
data class BuiltInWorkerToolDefinition(
    override val id: Long,
    override val name: String,
    override val description: String,
    override val config: JsonObject,
    override val inputSchema: JsonObject,
    override val outputSchema: JsonObject? = null,
    override val isEnabled: Boolean,
    override val createdAt: Instant,
    override val updatedAt: Instant,
    val workerId: Long,
    val builtInToolName: String,
) : ToolDefinition() {
    @SerialName("tool_type") // 'type' is a reserved property used by serialization
    override val type: ToolType = ToolType.BUILTIN_WORKER

    override fun withUpdatedAt(newUpdatedAt: Instant): ToolDefinition {
        return this.copy(updatedAt = newUpdatedAt)
    }
}

