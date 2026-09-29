package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Formats the concise, non-JSON operation summaries returned by the mutating project tools.
 *
 * `update_project` and `delete_project` deliberately do **not** return the full [ProjectDto] JSON:
 * they return a one-line plain-text description of the operation they just completed (mirroring
 * the agent-role mutating tools) so the LLM context stays lean. `create_project` is the explicit
 * exception — the request asks for the created project's full JSON (see [CreateProjectTool]); the
 * read-side tools (`list_projects`, `read_project`) return full JSON as well.
 */

/**
 * Formats the summary for a completed `update_project` operation.
 *
 * @param project The project state after the update (as returned by the project service).
 * @return Plain text like `Updated project 'Acme Web App' (id: 1).` (never JSON).
 */
internal fun formatUpdatedProject(project: ProjectDto): String =
    "Updated project '${project.name}' (id: ${project.id})."

/**
 * Formats the summary for a completed `delete_project` operation.
 *
 * Deleting a project is destructive for its member roles, so the summary mirrors
 * `formatDeletedAgentRole`: it names the deleted roles and the fate of the instruction rows they
 * linked, while never-linked library rows stay out of scope. Empty impact draws no clause.
 *
 * @param projectId The id of the deleted project the caller supplied.
 * @param deletedAgentRoleIds Member role ids deleted with the project; empty adds no clause.
 * @param deletedInstructionIds Instruction rows removed because their last link was held by a
 *            deleted role; empty adds no clause.
 * @param retainedInstructionIds Instruction rows kept because another role still links them; empty
 *            adds no clause.
 * @return Plain text like `Deleted project (id: 1); deleted 2 agent role(s) (ids: 10, 11); removed 1
 *         instruction(s) that lost their last link (ids: 3).` or `Deleted project (id: 1).` (never
 *         JSON).
 */
internal fun formatDeletedProject(
    projectId: Long,
    deletedAgentRoleIds: List<Long> = emptyList(),
    deletedInstructionIds: List<Long> = emptyList(),
    retainedInstructionIds: List<Long> = emptyList()
): String {
    val consequences = buildList {
        if (deletedAgentRoleIds.isNotEmpty()) {
            add(
                "deleted ${deletedAgentRoleIds.size} agent role(s) " +
                    "(ids: ${deletedAgentRoleIds.joinToString(", ")})"
            )
        }
        if (deletedInstructionIds.isNotEmpty()) {
            add(
                "removed ${deletedInstructionIds.size} instruction(s) that lost their last link " +
                    "(ids: ${deletedInstructionIds.joinToString(", ")})"
            )
        }
        if (retainedInstructionIds.isNotEmpty()) {
            add(
                "kept ${retainedInstructionIds.size} instruction(s) still linked by other role(s) " +
                    "(ids: ${retainedInstructionIds.joinToString(", ")})"
            )
        }
    }
    return if (consequences.isEmpty()) {
        "Deleted project (id: $projectId)."
    } else {
        "Deleted project (id: $projectId); ${consequences.joinToString("; ")}."
    }
}