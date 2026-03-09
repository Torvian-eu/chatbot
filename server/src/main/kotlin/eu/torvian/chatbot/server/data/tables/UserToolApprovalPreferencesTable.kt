package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.UserToolApprovalPreferencesTable.autoApprove
import eu.torvian.chatbot.server.data.tables.UserToolApprovalPreferencesTable.conditions
import eu.torvian.chatbot.server.data.tables.UserToolApprovalPreferencesTable.denialReason
import eu.torvian.chatbot.server.data.tables.UserToolApprovalPreferencesTable.toolDefinitionId
import eu.torvian.chatbot.server.data.tables.UserToolApprovalPreferencesTable.userId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Defines user-specific preferences for automatic tool call approval or denial.
 *
 * This table enables users to configure which tool calls should be automatically
 * approved or denied without manual intervention, streamlining the tool execution
 * workflow for trusted or unwanted tools.
 *
 * @property userId Reference to the user who owns this preference
 * @property toolDefinitionId Reference to the tool definition this preference applies to
 * @property autoApprove Whether to auto-approve (true) or auto-deny (false) tool calls
 * @property conditions Optional JSON string for conditional auto-approval logic (reserved for future use)
 * @property denialReason Optional reason text to provide when auto-denying (used by LLM, reserved for future use)
 */
object UserToolApprovalPreferencesTable : Table("user_tool_approval_preferences") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val toolDefinitionId = reference("tool_definition_id", ToolDefinitionTable, onDelete = ReferenceOption.CASCADE)
    val autoApprove = bool("auto_approve").default(true)
    val conditions = text("conditions").nullable()
    val denialReason = text("denial_reason").nullable()

    // Composite primary key: one preference per user-tool pair
    override val primaryKey = PrimaryKey(userId, toolDefinitionId)

    // Index for efficient lookups by user
    init {
        index(false, userId)
    }
}
