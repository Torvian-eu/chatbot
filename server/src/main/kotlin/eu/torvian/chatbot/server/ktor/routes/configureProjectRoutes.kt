package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.ProjectResource
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configures routes related to user-owned Projects (/api/v1/projects) using Ktor Resources.
 *
 * Projects are personal configuration in this stage, so every operation is scoped to the authenticated
 * user and the [ProjectService] verifies ownership before returning or mutating a project. A foreign or
 * nonexistent project collapses to the same not-found error, so no existence leak exists.
 *
 * Available endpoints:
 * - GET /api/v1/projects - List projects owned by the user (each with its member role ids)
 * - POST /api/v1/projects - Create a new project (the caller becomes the owner)
 * - GET /api/v1/projects/{projectId} - Get a specific project
 * - PUT /api/v1/projects/{projectId} - Update a specific project
 * - DELETE /api/v1/projects/{projectId} - Delete a specific project
 *
 * @param projectService Service backing the project CRUD operations.
 * @param authorizationService Authorization service retained for parity with the other resource routes;
 *            ownership enforcement is delegated to [ProjectService].
 */
fun Route.configureProjectRoutes(
    projectService: ProjectService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/projects - List all projects owned by the requesting user
        get<ProjectResource> {
            val userId = call.getUserId()
            call.respond(projectService.getAllProjectsForUser(userId))
        }

        // GET /api/v1/projects/{projectId} - Get project by ID (ownership checked)
        get<ProjectResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ e: ProjectError -> e.toApiError() }) {
                    projectService.getProjectById(userId, resource.projectId).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/projects - Create a new project owned by the requesting user
        post<ProjectResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateProjectRequest>()

            val result = either {
                withError({ e: CreateProjectError -> e.toApiError() }) {
                    projectService.createProject(userId, request).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // PUT /api/v1/projects/{projectId} - Update project (ownership checked)
        put<ProjectResource.ById> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UpdateProjectRequest>()

            val result = either {
                withError({ e: UpdateProjectError -> e.toApiError() }) {
                    projectService.updateProject(userId, resource.projectId, request).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/projects/{projectId} - Delete project (ownership checked; roles survive, the
        // affected sessions' roles are cleared by the service in the same transaction)
        delete<ProjectResource.ById> { resource ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: DeleteProjectError -> e.toApiError() }) {
                    projectService.deleteProject(userId, resource.projectId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}