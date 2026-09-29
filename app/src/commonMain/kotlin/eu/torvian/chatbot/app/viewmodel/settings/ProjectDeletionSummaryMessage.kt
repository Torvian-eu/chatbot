package eu.torvian.chatbot.app.viewmodel.settings

import eu.torvian.chatbot.common.models.api.project.DeleteProjectResponse

/**
 * Builds the user-facing toast text for a completed project deletion.
 *
 * The message reports counts only: this is a transient snackbar for a human, and naming every id
 * would be noise there, whereas the `delete_project` tool keeps the ids for the LLM. Clauses with a
 * zero count are omitted, so a project with no member roles toasts the base sentence alone.
 *
 * @param projectName The deleted project's name for the base sentence; `null` (the dialog was not
 *            carrying it) falls back to naming the id from [result].
 * @param result The deletion impact returned by the server.
 * @return The snackbar text, e.g. `Deleted project 'Research'. Deleted 2 agent role(s). Removed 1
 *         instruction(s) that lost their last link.`
 */
internal fun formatProjectDeletionSummary(
    projectName: String?,
    result: DeleteProjectResponse
): String {
    val base = if (projectName != null) {
        "Deleted project '$projectName'."
    } else {
        "Deleted project (id: ${result.projectId})."
    }
    val consequences = buildList {
        if (result.deletedAgentRoleIds.isNotEmpty()) {
            add("Deleted ${result.deletedAgentRoleIds.size} agent role(s).")
        }
        if (result.deletedInstructionIds.isNotEmpty()) {
            add("Removed ${result.deletedInstructionIds.size} instruction(s) that lost their last link.")
        }
        if (result.retainedInstructionIds.isNotEmpty()) {
            add("Kept ${result.retainedInstructionIds.size} instruction(s) still linked by other roles.")
        }
    }
    return buildString {
        append(base)
        consequences.forEach { clause ->
            append(' ')
            append(clause)
        }
    }
}
