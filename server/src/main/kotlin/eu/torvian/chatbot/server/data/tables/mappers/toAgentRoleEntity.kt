package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.data.tables.AgentRoleTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed [ResultRow] from `agent_roles` to an [AgentRoleEntity].
 *
 * The raw JSON `instructions_json` column is copied verbatim; parsing it into typed values happens in
 * the service layer so this mapper stays a pure table projection. The role's tool ids are NOT part of
 * this row — they live in the `agent_role_tools` join table and are loaded separately. The single
 * `project_id` membership column IS part of the row (a role belongs to at most one project).
 *
 * The role's model id and settings id are NOT part of this row either: they are derived at the service
 * layer from the referenced model preset (`model_preset_id`), which is the sole source of truth for the
 * role's LLM configuration.
 *
 * @receiver The result row produced by a query against [AgentRoleTable].
 * @return The corresponding [AgentRoleEntity].
 */
fun ResultRow.toAgentRoleEntity(): AgentRoleEntity = AgentRoleEntity(
    id = this[AgentRoleTable.id].value,
    name = this[AgentRoleTable.name],
    displayName = this[AgentRoleTable.displayName],
    description = this[AgentRoleTable.description],
    modelPresetId = this[AgentRoleTable.modelPresetId]?.value,
    instructionsJson = this[AgentRoleTable.instructionsJson],
    createdAt = Instant.fromEpochMilliseconds(this[AgentRoleTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[AgentRoleTable.updatedAt]),
    projectId = this[AgentRoleTable.projectId]?.value
)
