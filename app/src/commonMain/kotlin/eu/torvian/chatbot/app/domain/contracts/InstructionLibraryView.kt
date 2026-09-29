package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Selection modes of the instruction library view.
 *
 * The library is loaded once and every row names the roles that link it, so the modes are applied to
 * the delivered rows instead of narrowing the request: switching the selection costs no round trip.
 */
sealed interface InstructionLibraryFilter {

    /** Every instruction the user owns, linked or not. */
    data object All : InstructionLibraryFilter

    /**
     * The instructions one agent role links.
     *
     * @property roleId The role whose linked instructions are shown.
     */
    data class ByRole(val roleId: Long) : InstructionLibraryFilter

    /** The instructions no agent role links, which are the library's unused entries. */
    data object Unassigned : InstructionLibraryFilter
}

/**
 * Narrows a loaded library to one selection mode.
 *
 * @receiver The user's whole library, as the repository reports it.
 * @param selection The mode to apply.
 * @return The matching rows in their delivered order (all rows for [InstructionLibraryFilter.All]).
 */
fun List<AgentInstructionDto>.filterBy(selection: InstructionLibraryFilter): List<AgentInstructionDto> =
    when (selection) {
        InstructionLibraryFilter.All -> this
        InstructionLibraryFilter.Unassigned -> filter { it.linkedRoleIds.isEmpty() }
        is InstructionLibraryFilter.ByRole -> filter { selection.roleId in it.linkedRoleIds }
    }

/**
 * The selection modes the library view offers: the whole library, optionally the unassigned rows,
 * then one entry per role in the active project scope.
 *
 * The unassigned entry is withheld under a concrete project scope because an unassigned row has no
 * linked role and can therefore never match the project predicate; offering it would present an
 * impossible combination. It stays reachable whenever the project scope is
 * [AgentRoleFilter.AllProjects].
 *
 * @param roles The user's roles, as the role catalog reports them.
 * @param project The active project scope, which narrows the role entries.
 * @param projectsById The project catalog keyed by project id, used to resolve a role's project.
 * @return The dropdown options in display order.
 */
fun instructionFilterOptions(
    roles: List<AgentRoleDto>,
    project: AgentRoleFilter,
    projectsById: Map<Long, ProjectDto>
): List<InstructionLibraryFilter> = buildList {
    add(InstructionLibraryFilter.All)
    if (project == AgentRoleFilter.AllProjects) add(InstructionLibraryFilter.Unassigned)
    roles
        .filter { it.matchesProjectScope(project, projectsById) }
        .sortedWith(roleProjectOrder(projectsById))
        .forEach { add(InstructionLibraryFilter.ByRole(it.id)) }
}

/**
 * The user-facing label of one selection mode.
 *
 * @receiver The selection mode to label.
 * @param rolesById The role catalog keyed by role id, used to name a role selection.
 * @param projectsById The project catalog keyed by project id, used to name the role's project.
 * @param includeProject Whether a role entry repeats its project name. The list drops it while a
 *            project scope is active, because the scope already names the project for every entry.
 * @return The label shown in the filter control.
 */
fun InstructionLibraryFilter.optionLabel(
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>,
    includeProject: Boolean = true
): String = when (this) {
    InstructionLibraryFilter.All -> "All agent roles"
    InstructionLibraryFilter.Unassigned -> "Unassigned (no role)"
    is InstructionLibraryFilter.ByRole -> if (includeProject) {
        roleUsageLabel(roleId, rolesById, projectsById)
    } else {
        rolesById[roleId]?.roleLabel() ?: "Role #$roleId"
    }
}

/**
 * One agent role that links an instruction, as the usage list renders it.
 *
 * @property roleId The linking role's id.
 * @property label The role's user-facing label.
 */
data class InstructionUsage(
    val roleId: Long,
    val label: String
)

/**
 * Resolves the roles that link an instruction for display.
 *
 * Labels come from the role catalog the app already loads rather than from the server, and the rows
 * follow the reported role ids so the list order stays stable. A role missing from the catalog (a
 * stale catalog, or a role deleted elsewhere) is still listed with its id, so a link is never hidden.
 *
 * @receiver The instruction whose linking roles are resolved.
 * @param rolesById The role catalog keyed by role id.
 * @param projectsById The project catalog keyed by project id, used to qualify each role's label.
 * @return One usage entry per linking role.
 */
fun AgentInstructionDto.usage(
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>
): List<InstructionUsage> =
    linkedRoleIds.sorted().map { roleId ->
        InstructionUsage(roleId = roleId, label = roleUsageLabel(roleId, rolesById, projectsById))
    }

/**
 * The roles an instruction can still be assigned to: every role that does not link it yet.
 *
 * A role links a row at most once, so the roles it already links are excluded instead of being offered
 * and rejected by the server.
 *
 * @receiver The instruction to assign.
 * @param roles The user's roles, as the role catalog reports them.
 * @param projectsById The project catalog keyed by project id, used to order the roles by project.
 * @return The assignable roles in project-then-label order.
 */
fun AgentInstructionDto.assignableRoles(
    roles: List<AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>
): List<AgentRoleDto> =
    roles.filterNot { it.id in linkedRoleIds }.sortedWith(roleProjectOrder(projectsById))

/**
 * The user-facing label of an agent role: its display name when set, its machine name otherwise.
 *
 * @receiver The role to label.
 * @return The label shown in lists and pickers.
 */
fun AgentRoleDto.roleLabel(): String = displayName?.takeIf { it.isNotBlank() } ?: name

/**
 * The role's project id when that id resolves in the catalog, or null otherwise.
 *
 * An unassociated role (`projectId == null`) and a role whose project id is absent from the catalog
 * (a deleted project, or a catalog that has not loaded yet) are deliberately indistinguishable here:
 * both render as [NO_PROJECT_SECTION_TITLE] downstream.
 *
 * @receiver The role whose project is resolved.
 * @param projectsById The project catalog keyed by project id.
 * @return The resolved project id, or null when the role has none or it cannot be resolved.
 */
internal fun AgentRoleDto.resolvedProjectId(projectsById: Map<Long, ProjectDto>): Long? =
    projectId?.takeIf(projectsById::containsKey)

/**
 * The role's label qualified with its project name, so identically named roles stay distinguishable.
 *
 * The format is `<role label> — <project name>`, falling back to `<role label> — No project` for an
 * unassociated role or one whose project does not resolve in the catalog. The project component is
 * never suppressed, not even when the user owns a single project or the role is the only one linked.
 *
 * @receiver The role to label.
 * @param projectsById The project catalog keyed by project id.
 * @return The project-qualified role label.
 */
fun AgentRoleDto.roleWithProjectLabel(projectsById: Map<Long, ProjectDto>): String =
    "${roleLabel()} — ${resolvedProjectName(projectsById) ?: NO_PROJECT_SECTION_TITLE}"

/**
 * The project-qualified label of a linking role id, resolving the role from the catalog.
 *
 * A role id absent from the catalog keeps its id in the label and is never attributed to a project,
 * so a link is reported even when the role cannot be resolved.
 *
 * @param roleId The linking role's id.
 * @param rolesById The role catalog keyed by role id.
 * @param projectsById The project catalog keyed by project id.
 * @return The project-qualified role label, or `"Role #<id> — No project"` when the role is unknown.
 */
fun roleUsageLabel(
    roleId: Long,
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>
): String = rolesById[roleId]?.roleWithProjectLabel(projectsById)
    ?: "Role #$roleId — $NO_PROJECT_SECTION_TITLE"

/**
 * Whether an instruction has a link whose role falls in the project scope of [filter].
 *
 * [AgentRoleFilter.AllProjects] matches every row. A concrete project matches when some linked role
 * resolves to it. [AgentRoleFilter.NoProject] matches when some linked role resolves to no project,
 * which covers unassociated roles, roles whose project id is not in the catalog, and linked role ids
 * missing from the role catalog (their project is unknown). A row with no links matches only
 * [AgentRoleFilter.AllProjects].
 *
 * @receiver The instruction whose links are scoped.
 * @param filter The active project scope.
 * @param rolesById The role catalog keyed by role id.
 * @param projectsById The project catalog keyed by project id.
 * @return True when at least one linked role falls in the scope.
 */
fun AgentInstructionDto.matchesProjectScope(
    filter: AgentRoleFilter,
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>
): Boolean = when (filter) {
    AgentRoleFilter.AllProjects -> true
    is AgentRoleFilter.Project ->
        linkedRoleIds.any { roleId -> rolesById[roleId]?.resolvedProjectId(projectsById) == filter.projectId }

    AgentRoleFilter.NoProject ->
        linkedRoleIds.any { roleId -> rolesById[roleId].resolvedProjectIdOrNull(projectsById) == null }
}

/**
 * AND-composes the project scope onto an already role-filtered library.
 *
 * @receiver The library, already narrowed by the role selection.
 * @param filter The active project scope.
 * @param rolesById The role catalog keyed by role id.
 * @param projectsById The project catalog keyed by project id.
 * @return The matching rows in their delivered order (all rows for [AgentRoleFilter.AllProjects]).
 */
fun List<AgentInstructionDto>.filterByProject(
    filter: AgentRoleFilter,
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>
): List<AgentInstructionDto> = when (filter) {
    AgentRoleFilter.AllProjects -> this
    else -> filter { it.matchesProjectScope(filter, rolesById, projectsById) }
}

/**
 * Whether a role belongs to the project scope of [filter].
 *
 * @receiver The role to test.
 * @param filter The active project scope.
 * @param projectsById The project catalog keyed by project id.
 * @return True when the role's resolved project falls in the scope.
 */
private fun AgentRoleDto.matchesProjectScope(
    filter: AgentRoleFilter,
    projectsById: Map<Long, ProjectDto>
): Boolean = when (filter) {
    AgentRoleFilter.AllProjects -> true
    is AgentRoleFilter.Project -> resolvedProjectId(projectsById) == filter.projectId
    AgentRoleFilter.NoProject -> resolvedProjectId(projectsById) == null
}

/**
 * The role's project name when it resolves, or null when the role has no resolvable project.
 *
 * @receiver The role whose project name is resolved.
 * @param projectsById The project catalog keyed by project id.
 * @return The resolved project name, or null.
 */
private fun AgentRoleDto.resolvedProjectName(projectsById: Map<Long, ProjectDto>): String? =
    resolvedProjectId(projectsById)?.let { projectsById.getValue(it).name }

/**
 * The project id of a possibly missing role, or null when the role is absent from the catalog.
 *
 * A missing role is treated as having no project, so its link still matches the "No project" scope.
 *
 * @receiver The role to resolve, or null when the catalog has no such role.
 * @param projectsById The project catalog keyed by project id.
 * @return The resolved project id, or null.
 */
private fun AgentRoleDto?.resolvedProjectIdOrNull(projectsById: Map<Long, ProjectDto>): Long? =
    this?.resolvedProjectId(projectsById)

/**
 * The display order of roles in the filter options and the assign picker.
 *
 * Named projects come first by case-insensitive project name, roles without a resolvable project
 * trail, and roles are ordered by case-insensitive label with the id as the final tie-break.
 *
 * @param projectsById The project catalog keyed by project id.
 * @return The comparator applied to the role list.
 */
private fun roleProjectOrder(projectsById: Map<Long, ProjectDto>): Comparator<AgentRoleDto> =
    compareBy(
        { it.resolvedProjectId(projectsById) == null },
        { it.resolvedProjectName(projectsById)?.lowercase() },
        { it.roleLabel().lowercase() },
        { it.id }
    )