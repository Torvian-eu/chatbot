package eu.torvian.chatbot.server.service.core.project

/**
 * Outcome of a successful project deletion, naming the cascade it performed.
 *
 * Deleting a project removes its member agent roles and, with them, the instruction rows that lose
 * their last link; rows still linked by a surviving role survive. The result reports both sets so
 * every surface can describe the same impact.
 *
 * @property deletedAgentRoleIds Ids of the member roles removed with the project.
 * @property deletedInstructionIds Instruction rows removed because their last link was held by one
 *           of the deleted roles.
 * @property retainedInstructionIds Instruction rows the deleted roles linked that a surviving role
 *           still links, so they were kept. Never overlaps [deletedInstructionIds].
 */
data class DeleteProjectResult(
    val deletedAgentRoleIds: List<Long>,
    val deletedInstructionIds: List<Long>,
    val retainedInstructionIds: List<Long>
)
