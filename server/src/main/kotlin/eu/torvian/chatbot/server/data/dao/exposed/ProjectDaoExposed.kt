package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.error.project.ProjectError
import eu.torvian.chatbot.server.data.entities.ProjectEntity
import eu.torvian.chatbot.server.data.tables.ProjectOwnersTable
import eu.torvian.chatbot.server.data.tables.ProjectTable
import eu.torvian.chatbot.server.data.tables.mappers.toProjectEntity
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of the [ProjectDao].
 *
 * All operations are single-row reads/writes on `projects`. The owner lives in `project_owners` and
 * the member-role ids via the single nullable `agent_roles.project_id` column; both are loaded
 * through their own DAOs so this class
 * stays a plain table projection.
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class ProjectDaoExposed(
    private val transactionScope: TransactionScope
) : ProjectDao {

    override suspend fun getAllProjectsForUser(userId: Long): List<ProjectEntity> =
        transactionScope.transaction {
            ProjectTable
                .join(
                    ProjectOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ProjectTable.id eq ProjectOwnersTable.projectId }
                )
                .selectAll()
                .where { ProjectOwnersTable.userId eq userId }
                .map { it.toProjectEntity() }
        }

    override suspend fun getProjectById(id: Long): Either<ProjectError.NotFound, ProjectEntity> =
        transactionScope.transaction {
            either {
                val entity = ProjectTable.selectAll().where { ProjectTable.id eq id }
                    .singleOrNull()
                    ?.toProjectEntity()
                ensure(entity != null) { ProjectError.NotFound(id) }
                entity
            }
        }

    override suspend fun getProjectsByIdsForUser(userId: Long, ids: List<Long>): List<ProjectEntity> =
        transactionScope.transaction {
            if (ids.isEmpty()) return@transaction emptyList()
            val entitiesById = ProjectTable
                .join(
                    ProjectOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ProjectTable.id eq ProjectOwnersTable.projectId }
                )
                .selectAll()
                .where {
                    (ProjectOwnersTable.userId eq userId) and (ProjectTable.id inList ids)
                }
                .map { it.toProjectEntity() }
                .associateBy { it.id }
            // SQL does not guarantee IN-list order; restore the caller's order where present.
            ids.mapNotNull { entitiesById[it] }
        }

    override suspend fun projectNameExistsForUser(userId: Long, name: String): Boolean =
        transactionScope.transaction {
            ProjectTable
                .join(
                    ProjectOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { ProjectTable.id eq ProjectOwnersTable.projectId }
                )
                .selectAll()
                .where { (ProjectOwnersTable.userId eq userId) and (ProjectTable.name eq name) }
                .count() > 0
        }

    override suspend fun insertProject(name: String, description: String): ProjectEntity =
        transactionScope.transaction {
            val now = System.currentTimeMillis()
            val insertStatement = ProjectTable.insert {
                it[ProjectTable.name] = name
                it[ProjectTable.description] = description
                it[ProjectTable.createdAt] = now
                it[ProjectTable.updatedAt] = now
            }
            insertStatement.resultedValues?.first()?.toProjectEntity()
                ?: throw IllegalStateException("Failed to retrieve newly inserted project")
        }

    override suspend fun updateProject(project: ProjectEntity): Either<ProjectError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ProjectTable.update({ ProjectTable.id eq project.id }) {
                    it[ProjectTable.name] = project.name
                    it[ProjectTable.description] = project.description
                    it[ProjectTable.updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { ProjectError.NotFound(project.id) }
            }
        }

    override suspend fun deleteProject(id: Long): Either<ProjectError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ProjectTable.deleteWhere { ProjectTable.id eq id }
                ensure(deletedCount != 0) { ProjectError.NotFound(id) }
            }
        }
}