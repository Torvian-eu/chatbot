package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for user-owned project endpoints.
 *
 * This resource defines the URL structure for project CRUD operations:
 * - GET /api/v1/projects - List projects owned by the user
 * - POST /api/v1/projects - Create a new project (the caller becomes the owner)
 * - GET /api/v1/projects/{projectId} - Get a specific project (with member role ids)
 * - PUT /api/v1/projects/{projectId} - Update a specific project
 * - DELETE /api/v1/projects/{projectId} - Delete a specific project
 * - POST /api/v1/projects/{projectId}/clone - Clone a specific project (deep-copies its member roles)
 */
@Resource("projects")
class ProjectResource(val parent: Api = Api()) {
    /**
     * Resource for operations on a specific project by ID.
     *
     * @property parent The parent [ProjectResource].
     * @property projectId The unique identifier of the project.
     */
    @Resource("{projectId}")
    class ById(val parent: ProjectResource = ProjectResource(), val projectId: Long) {
        /**
         * Resource for cloning a project under a new, per-owner-unique name:
         * POST /api/v1/projects/{projectId}/clone. The body carries the new name and an optional
         * description override ([eu.torvian.chatbot.common.models.api.project.CloneProjectRequest]);
         * the member agent roles of the source are deep-copied as new role rows bound to the clone.
         *
         * @property parent The parent [ById] resource carrying the source project id.
         */
        @Resource("clone")
        class Clone(val parent: ById)
    }
}