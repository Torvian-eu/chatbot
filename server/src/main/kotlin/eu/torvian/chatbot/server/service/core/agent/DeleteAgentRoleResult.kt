package eu.torvian.chatbot.server.service.core.agent

/**
 * Outcome of a successful agent-role deletion, naming the fate of every instruction row the role
 * linked.
 *
 * The deletion is content-destructive for rows it leaves without any link, so callers report both
 * sets: what was removed and what survives because another role still links it. Never-linked library
 * rows are outside a role's link set and never appear here.
 *
 * @property deletedInstructionIds Linked rows removed with the role because no link remained, in
 *           candidate order.
 * @property retainedInstructionIds Linked rows kept because at least one other role still links
 *           them, in candidate order.
 */
data class DeleteAgentRoleResult(
    val deletedInstructionIds: List<Long>,
    val retainedInstructionIds: List<Long>
)
