package eu.torvian.chatbot.common.models.api.project

import kotlinx.serialization.Serializable

/**
 * Response body for deleting a user-owned project.
 *
 * Deleting a project is destructive: the project's member agent roles are deleted and, with them,
 * every instruction row that loses its last link through those deletions. Instruction rows still
 * linked by a surviving role (another project's role or an unassociated role) are untouched. The body
 * reports that impact so clients can refresh their caches and surface the outcome; [projectId] makes
 * the deletion self-describing without relying on the request.
 *
 * @property projectId The id of the deleted project.
 * @property deletedAgentRoleIds Ids of the member roles deleted with the project.
 * @property deletedInstructionIds Ids of the instruction rows removed because their last link was
 *            held by one of the deleted roles.
 * @property retainedInstructionIds Ids of the instruction rows the deleted roles linked that a
 *            surviving role still links, so they were kept.
 */
@Serializable
data class DeleteProjectResponse(
    val projectId: Long,
    val deletedAgentRoleIds: List<Long>,
    val deletedInstructionIds: List<Long>,
    val retainedInstructionIds: List<Long>
)
