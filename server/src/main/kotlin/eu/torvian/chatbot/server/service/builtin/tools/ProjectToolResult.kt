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
 * @param projectId The id of the deleted project (the delete service returns no payload, so the
 *            message carries the id the caller supplied).
 * @return Plain text like `Deleted project (id: 1).` (never JSON).
 */
internal fun formatDeletedProject(projectId: Long): String =
    "Deleted project (id: $projectId)."