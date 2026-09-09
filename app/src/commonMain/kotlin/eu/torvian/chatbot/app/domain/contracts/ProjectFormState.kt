package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.MAX_PROJECT_NAME_LENGTH
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Mutable draft of a user-owned project being created or edited in the management form.
 *
 * All fields are held as plain values so the form can build a [CreateProjectRequest] or
 * [UpdateProjectRequest] on save.
 *
 * @property mode Whether this draft creates a new project or edits an existing one.
 * @property projectId Existing project id while editing.
 * @property name Unique (per owner) project name.
 * @property description Free-form description of the project's purpose.
 * @property agentRoleIds Unordered set of agent-role identifiers belonging to the project (full
 *            replacement on save). Roles have single-project membership, so the server rejects any
 *            member that is already bound to a different project; the settings detail panel renders
 *            membership from the same set.
 * @property errorMessage Optional validation error surfaced to the form.
 */
data class ProjectFormState(
    val mode: FormMode = FormMode.NEW,
    val projectId: Long? = null,
    val name: String = "",
    val description: String = "",
    val agentRoleIds: Set<Long> = emptySet(),
    val errorMessage: String? = null
) {

    /**
     * Copies this draft with an updated error message, preserving all other fields.
     */
    fun withError(errorMessage: String?): ProjectFormState = copy(errorMessage = errorMessage)

    /**
     * Validates the required fields. Only the name is mandatory; member roles are optional.
     *
     * @return A human-readable validation message, or null when the draft is valid.
     */
    fun validate(): String? {
        if (name.isBlank()) return "Project name cannot be empty."
        if (name.length > MAX_PROJECT_NAME_LENGTH) {
            return "Project name cannot exceed $MAX_PROJECT_NAME_LENGTH characters."
        }
        return null
    }

    /**
     * Builds a [CreateProjectRequest] from this draft. Only valid when [mode] is NEW and
     * [validate] returns null.
     */
    fun toCreateRequest(): CreateProjectRequest = CreateProjectRequest(
        name = name.trim(),
        description = description.trim(),
        agentRoleIds = agentRoleIds
    )

    /**
     * Builds a [UpdateProjectRequest] from this draft. Only valid when [mode] is EDIT and
     * [validate] returns null.
     */
    fun toUpdateRequest(): UpdateProjectRequest = UpdateProjectRequest(
        name = name.trim(),
        description = description.trim(),
        agentRoleIds = agentRoleIds
    )
}

/**
 * Creates an empty draft for a new project: no name, no description, no member roles.
 *
 * @return A new [ProjectFormState] in NEW mode.
 */
fun createEmptyProjectForm(): ProjectFormState = ProjectFormState(
    mode = FormMode.NEW
)

/**
 * Creates an edit draft from an existing project, preserving its member roles.
 *
 * @receiver The project to edit.
 * @return A [ProjectFormState] in EDIT mode pre-filled from the project.
 */
fun ProjectDto.toEditFormState(): ProjectFormState = ProjectFormState(
    mode = FormMode.EDIT,
    projectId = id,
    name = name,
    description = description,
    agentRoleIds = agentRoleIds
)

/**
 * Consolidated dialog state for the Projects management tab.
 */
sealed class ProjectDialogState {
    /** No dialog is currently visible. */
    object None : ProjectDialogState()

    /** Add-project form dialog. */
    data class AddProject(
        val formState: ProjectFormState
    ) : ProjectDialogState()

    /** Edit-project form dialog. */
    data class EditProject(
        val project: ProjectDto,
        val formState: ProjectFormState
    ) : ProjectDialogState()

    /** Clone-project dialog: new name (prefilled `Copy of <name>`) plus an optional description
     * override prefilled from the source; the member roles are deep-copied server-side. */
    data class CloneProject(
        val project: ProjectDto,
        val formState: ProjectFormState
    ) : ProjectDialogState()

    /** Delete-project confirmation dialog. */
    data class DeleteProject(
        val project: ProjectDto
    ) : ProjectDialogState()
}