package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Enumeration of tool call execution statuses.
 *
 * Represents the current state of a tool call in its lifecycle from request to completion.
 *
 * Lifecycle:
 * 1. PENDING - Tool call is queued for execution
 * 2. AWAITING_APPROVAL - Waiting for user to approve/deny execution
 * 3. EXECUTING - Tool is actively executing after user approval
 * 4. SUCCESS - Tool executed successfully
 * 5. ERROR - Tool execution failed
 * 6. USER_DENIED - User explicitly denied the tool call execution
 */
@Serializable
enum class ToolCallStatus {
    /** Tool call is queued or in progress */
    PENDING,

    /** Tool call is waiting for user approval */
    AWAITING_APPROVAL,

    /** Tool is actively executing after approval */
    EXECUTING,

    /** Tool executed successfully */
    SUCCESS,

    /** Tool execution failed */
    ERROR,

    /** User explicitly denied the tool call execution */
    USER_DENIED
}

