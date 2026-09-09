package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.ProjectEntity
import eu.torvian.chatbot.server.data.tables.ProjectTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed [ResultRow] from `projects` to a [ProjectEntity].
 *
 * The member-role ids and the owner are not part of this row — membership lives in the single
 * nullable `agent_roles.project_id` column
 * and `project_owners` respectively and are loaded separately.
 *
 * @receiver The result row produced by a query against [ProjectTable].
 * @return The corresponding [ProjectEntity].
 */
fun ResultRow.toProjectEntity(): ProjectEntity = ProjectEntity(
    id = this[ProjectTable.id].value,
    name = this[ProjectTable.name],
    description = this[ProjectTable.description],
    createdAt = Instant.fromEpochMilliseconds(this[ProjectTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[ProjectTable.updatedAt])
)