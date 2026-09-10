package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * A single grouped section of the Settings → Agent Roles list.
 *
 * Each section has a header label and the roles rendered beneath it. Sections are produced by
 * [buildAgentRoleSections] from the already-loaded role and project streams; the roles keep the
 * repository order so the list stays stable and deterministic across recompositions.
 *
 * @property title Section header text shown above the roles.
 * @property projectId Project scope of the section, or `null` for the "No project" section.
 * @property roles Roles belonging to this section, in repository order.
 */
data class AgentRoleSection(
    val title: String,
    val projectId: Long?,
    val roles: List<AgentRoleDto>
)

/** Label used for the section (and filter option) that groups unassociated roles. */
const val NO_PROJECT_SECTION_TITLE = "No project"

/**
 * Project scope options for the Settings → Agent Roles filter.
 *
 * The sealed hierarchy keeps the filter type-safe: "All projects" is a single option, each project
 * is identified by its id, and "No project" targets unassociated roles.
 */
sealed interface AgentRoleFilter {
    /** Shows every grouped section (the default). */
    data object AllProjects : AgentRoleFilter

    /** Narrows the list to the roles of a single project. */
    data class Project(val projectId: Long) : AgentRoleFilter

    /** Narrows the list to unassociated roles. */
    data object NoProject : AgentRoleFilter
}

/**
 * Human-readable label for a [AgentRoleFilter] option, used in the filter dropdown.
 *
 * Project options normally render with their project name (resolved by the page); the "Project #id"
 * fallback only surfaces when a saved filter references a project that no longer exists.
 */
val AgentRoleFilter.displayLabel: String
    get() = when (this) {
        AgentRoleFilter.AllProjects -> "All projects"
        is AgentRoleFilter.Project -> "Project #$projectId"
        AgentRoleFilter.NoProject -> NO_PROJECT_SECTION_TITLE
    }

/**
 * Filters the grouped [sections] down to [filter].
 *
 * [AgentRoleFilter.AllProjects] keeps every section (the full grouped list). A project scope keeps
 * only that project's section, "No project" keeps only the unassociated section. Section roles keep
 * their repository order; the returned list is ordered exactly like the input so the filter never
 * reshuffles the UI.
 *
 * @param sections The grouped sections produced by [buildAgentRoleSections].
 * @param filter The active project scope.
 * @return The matching sections; may be empty when the scope has no roles.
 */
fun filterAgentRoleSections(sections: List<AgentRoleSection>, filter: AgentRoleFilter): List<AgentRoleSection> =
    when (filter) {
        AgentRoleFilter.AllProjects -> sections
        is AgentRoleFilter.Project -> sections.filter { it.projectId == filter.projectId }
        AgentRoleFilter.NoProject -> sections.filter { it.projectId == null }
    }

/**
 * Builds the ordered role sections for the Settings → Agent Roles list.
 *
 * Roles are grouped by their single project membership: one section per project (ordered by
 * project name) with its member roles beneath it, plus a final "No project" section holding every
 * role without a resolvable project — unassociated roles (`projectId == null`) and roles whose
 * project id no longer resolves (deleted project, or the project stream has not loaded yet). The
 * fallback keeps such roles visible instead of dropping them from the list. Projects without member
 * roles are omitted so empty projects do not produce empty headers. The derivation is pure and
 * reactive-friendly: callers re-run it whenever the role or project stream emits.
 *
 * @param roles All agent roles owned by the current user (repository order is preserved within
 *            each section).
 * @param projects All projects owned by the current user, used for the section headers (sorted by
 *            name; display name is the plain name).
 * @return The ordered sections, project sections first then the "No project" section, omitting
 *         projects without member roles and skipping the "No project" section when no
 *         unresolvable/unassociated roles exist.
 */
fun buildAgentRoleSections(roles: List<AgentRoleDto>, projects: List<ProjectDto>): List<AgentRoleSection> {
    val projectsById = projects.associateBy { it.id }
    // Roles whose project id is null OR does not resolve in the project stream share the same fate:
    // they cannot render under a named header, so they are grouped as "No project" (see below).
    val (associated, unresolvable) = roles.partition { role -> role.projectId?.let(projectsById::containsKey) == true }
    val projectSections = associated
        .groupBy { role -> projectsById.getValue(role.projectId!!) }
        .entries
        .sortedBy { (project, _) -> project.name }
        .map { (project, sectionRoles) ->
            AgentRoleSection(
                title = project.name,
                projectId = project.id,
                roles = sectionRoles
            )
        }
    return if (unresolvable.isEmpty()) {
        projectSections
    } else {
        projectSections + AgentRoleSection(
            title = NO_PROJECT_SECTION_TITLE,
            projectId = null,
            roles = unresolvable
        )
    }
}
