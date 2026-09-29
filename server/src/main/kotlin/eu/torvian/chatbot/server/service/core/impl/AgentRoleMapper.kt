package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.agent.AgentInstruction
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.agent.AgentRoleSummary
import eu.torvian.chatbot.server.service.core.agent.CustomInstruction
import eu.torvian.chatbot.server.service.core.agent.MainInstruction
import eu.torvian.chatbot.server.service.core.agent.ModelSpecificInstruction
import eu.torvian.chatbot.server.service.core.agent.RoleInstruction
import eu.torvian.chatbot.server.service.core.agent.SpawnableAgentsAdvertisement
import eu.torvian.chatbot.server.service.core.agent.SpawnableAgentsInstruction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Representation mapping for agent roles: stored row ↔ server domain ↔ wire DTO.
 *
 * Instructions are normalized rows linked to the role through `agent_role_instructions`; callers
 * always supply the already-loaded ordered instruction rows plus the per-row linking-role ids (one
 * batched reverse-link lookup per read), so the mapper never performs its own instruction queries
 * and the single-role and list paths share one funnel. The same holds for the other resolved
 * relations (`tools`, `spawnableRoleIds`, `preset`, `disabled`): the mapper performs no lookups of
 * its own.
 *
 * @property agentRoleDao DAO used by the spawn allow-list advertisement loader (owner-scoped role
 *            reads).
 * @property toolDefinitionDao DAO used by the `spawn_agent`-availability loader (tool reads scoped
 *            to the role's tool ids).
 * @property projectDao DAO used to resolve the project labels of the advertisement (one batched read
 *            covering the targets' projects and the owning role's own project).
 * @property json Shared JSON codec used to parse the raw `custom` JSON text of stored instructions.
 */
internal class AgentRoleMapper(
    private val agentRoleDao: AgentRoleDao,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val projectDao: ProjectDao,
    private val json: Json
) {
    companion object {
        /** Logger used for dropped-instruction diagnostics (malformed stored instructions). */
        private val logger: Logger = LogManager.getLogger(AgentRoleMapper::class.java)
    }

    /**
     * Converts a stored role into the domain type while retaining current, ownership-scoped prompt
     * loaders for dynamic instructions.
     *
     * Single row→domain funnel: the role row stores only the preset reference, so the domain role's
     * derived `modelId`/`modelSettingsId` come from the resolved [preset] (null when absent or
     * unset).
     *
     * @param entity Stored role row to convert.
     * @param tools Attached tool ids.
     * @param spawnableRoleIds Unordered target role ids.
     * @param projectId The single project id the role belongs to (null = unassociated).
     * @param preset The role's resolved model preset, or null when the role is preset-less (or the
     *            reference is dangling). Its references supply the domain role's derived
     *            `modelId`/`modelSettingsId`.
     * @param ownerId Owner used to scope dynamic target-summary queries.
     * @param disabled Whether the role is disabled for the requesting user (side-table derived).
     * @param instructions The role's instruction rows in their `sequence` order.
     * @param linkedRoleIdsByInstructionId Linking-role ids per instruction row, as resolved by one
     *            batched reverse-link lookup.
     * @return Domain role with lazy instruction sources.
     */
    fun toDomain(
        entity: AgentRoleEntity,
        tools: Set<Long>,
        spawnableRoleIds: Set<Long>,
        projectId: Long?,
        preset: ModelPresetEntity?,
        ownerId: Long,
        disabled: Boolean,
        instructions: List<InstructionEntity>,
        linkedRoleIdsByInstructionId: Map<Long, Set<Long>>
    ): AgentRole = AgentRole(
        id = entity.id,
        name = entity.name,
        displayName = entity.displayName,
        description = entity.description,
        // Derived, read-only convenience values: the role row stores only the preset reference, so the
        // model/settings ids come from the resolved preset (null when absent or unset).
        modelId = preset?.modelId,
        modelSettingsId = preset?.modelSettingsId,
        modelPresetId = entity.modelPresetId,
        tools = tools,
        spawnableAgentRoleIds = spawnableRoleIds,
        projectId = projectId,
        instructions = instructions.mapNotNull {
            toDomainInstruction(
                row = it,
                linkedRoleIds = linkedRoleIdsByInstructionId[it.id].orEmpty(),
                ownerId = ownerId,
                spawnableRoleIds = spawnableRoleIds,
                roleToolIds = tools,
                currentProjectId = projectId
            )
        },
        disabled = disabled
    )

    /**
     * Single row→wire funnel: composes [toDomain] with the DTO mapping, so call sites stay one
     * expression.
     *
     * @param entity Stored role row to convert.
     * @param tools Attached tool ids.
     * @param spawnableRoleIds Unordered target role ids.
     * @param projectId The single project id the role belongs to (null = unassociated).
     * @param preset The role's resolved model preset, or null when the role is preset-less (or the
     *            reference is dangling).
     * @param ownerId Owner used to scope dynamic target-summary queries.
     * @param disabled Whether the role is disabled for the requesting user (side-table derived).
     * @param instructions The role's instruction rows in their `sequence` order.
     * @param linkedRoleIdsByInstructionId Linking-role ids per instruction row.
     * @return The corresponding [AgentRoleDto] with resolved instruction messages.
     */
    suspend fun toDto(
        entity: AgentRoleEntity,
        tools: Set<Long>,
        spawnableRoleIds: Set<Long>,
        projectId: Long?,
        preset: ModelPresetEntity?,
        ownerId: Long,
        disabled: Boolean,
        instructions: List<InstructionEntity>,
        linkedRoleIdsByInstructionId: Map<Long, Set<Long>>
    ): AgentRoleDto = toDto(
        toDomain(
            entity = entity,
            tools = tools,
            spawnableRoleIds = spawnableRoleIds,
            projectId = projectId,
            preset = preset,
            ownerId = ownerId,
            disabled = disabled,
            instructions = instructions,
            linkedRoleIdsByInstructionId = linkedRoleIdsByInstructionId
        )
    )

    /**
     * Loads the advertisement of a role's spawn allow-list: the resolvable targets (with their project
     * data) plus the owning role's own project.
     *
     * The allow-list itself is the authority: targets from any project scope are returned, and ids that
     * do not resolve to a role of [ownerId] are dropped, so the table lists exactly what
     * `spawn_agent` accepts. Project labels come from a single batched read covering the targets'
     * distinct projects and [currentProjectId] together (no per-target query). The returned targets are
     * sorted by displayed label (id ascending as the tie-break), so the rendered order never depends on
     * query or map order.
     *
     * @param ownerId Owner whose roles and projects may be resolved.
     * @param spawnableAgentRoleIds Unordered target role ids of the role's allow-list.
     * @param currentProjectId Project of the role owning the instruction, or null when unassociated.
     * @return The advertisement, targets already ordered for rendering.
     */
    suspend fun loadSpawnableAgentsAdvertisement(
        ownerId: Long,
        spawnableAgentRoleIds: Set<Long>,
        currentProjectId: Long?
    ): SpawnableAgentsAdvertisement {
        val targets = agentRoleDao.getRolesByIdsForUser(ownerId, spawnableAgentRoleIds.toList())
        // The allow-list is a set, so no persisted order exists; the project ids are distinct so the
        // single batched project read stays proportional to the number of projects, not targets.
        val projectIds = (targets.mapNotNull { it.projectId } + listOfNotNull(currentProjectId)).distinct()
        val projectsById = if (projectIds.isEmpty()) {
            emptyMap()
        } else {
            projectDao.getProjectsByIdsForUser(ownerId, projectIds).associateBy { it.id }
        }
        return SpawnableAgentsAdvertisement(
            currentProjectId = currentProjectId,
            currentProjectName = currentProjectId?.let { projectsById[it]?.name },
            targets = targets.map { target ->
                AgentRoleSummary(
                    id = target.id,
                    name = target.name,
                    displayName = target.displayName,
                    description = target.description,
                    projectId = target.projectId,
                    projectName = target.projectId?.let { projectsById[it]?.name }
                )
            }.sortedWith(compareBy({ it.displayLabel.lowercase() }, { it.id }))
        )
    }

    /**
     * Converts a server domain [AgentRole] into a wire [AgentRoleDto], resolving every instruction
     * message first so the DTO always carries non-null, current text.
     *
     * @param role The domain role to convert.
     * @return The corresponding [AgentRoleDto].
     */
    private suspend fun toDto(role: AgentRole): AgentRoleDto = AgentRoleDto(
        id = role.id,
        name = role.name,
        displayName = role.displayName,
        description = role.description,
        modelId = role.modelId,
        modelSettingsId = role.modelSettingsId,
        modelPresetId = role.modelPresetId,
        tools = role.tools,
        spawnableAgentRoleIds = role.spawnableAgentRoleIds,
        instructions = role.instructions.map { toDtoInstruction(it) },
        disabled = role.disabled,
        projectId = role.projectId
    )

    /**
     * Maps a stored instruction row to its server domain subtype.
     *
     * Dispatches on the row's `type` string. The `else` branch logs a warning and returns null for
     * unknown or unrecognized kinds, so forward-compatible rows don't silently apply unrecognized
     * semantics. The row id and linking-role ids are carried onto every subtype so the wire mapping
     * can report them.
     *
     * @param row The stored instruction row to convert.
     * @param linkedRoleIds Ids of the roles linking the row.
     * @param ownerId Owner scope for dynamic advertisement resolution.
     * @param spawnableRoleIds Unordered target ids used by the dynamic marker.
     * @param roleToolIds Tool ids used to determine whether `spawn_agent` is enabled.
     * @param currentProjectId Project of the role being mapped, used by the advertisement's intro.
     * @return The corresponding [AgentInstruction], or null when the kind is unrecognized or a
     *         `model_specific` row is missing its `modelId` in `custom` (both logged as warnings —
     *         they indicate a database inconsistency).
     */
    private fun toDomainInstruction(
        row: InstructionEntity,
        linkedRoleIds: Set<Long>,
        ownerId: Long,
        spawnableRoleIds: Set<Long>,
        roleToolIds: Set<Long>,
        currentProjectId: Long?
    ): AgentInstruction? = when (row.type) {
        AgentInstructionTypes.SPAWNABLE_AGENTS -> SpawnableAgentsInstruction(
            name = row.name,
            advertisementLoader = {
                loadSpawnableAgentsAdvertisement(ownerId, spawnableRoleIds, currentProjectId)
            },
            spawnAgentToolAvailableLoader = {
                toolDefinitionDao.getToolDefinitionsByIds(roleToolIds)
                    .any { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
            },
            id = row.id,
            linkedRoleIds = linkedRoleIds
        )

        AgentInstructionTypes.ROLE -> RoleInstruction(
            id = row.id,
            name = row.name,
            message = row.message.orEmpty(),
            linkedRoleIds = linkedRoleIds
        )

        AgentInstructionTypes.MAIN -> MainInstruction(
            id = row.id,
            name = row.name,
            message = row.message.orEmpty(),
            linkedRoleIds = linkedRoleIds
        )

        AgentInstructionTypes.CUSTOM -> CustomInstruction(
            id = row.id,
            name = row.name,
            message = row.message.orEmpty(),
            linkedRoleIds = linkedRoleIds
        )

        AgentInstructionTypes.MODEL_SPECIFIC -> {
            val targetModelId = parseCustom(row.custom).modelIdOrNull()
            if (targetModelId == null) {
                // A model_specific instruction without a modelId is a data integrity issue: the
                // stored custom JSON was malformed or partially migrated. Log it and drop the
                // instruction rather than crashing role retrieval.
                logger.warn(
                    "Dropping model_specific instruction '{}' for role retrieval: missing 'modelId' in custom",
                    row.name
                )
                null
            } else {
                ModelSpecificInstruction(
                    name = row.name,
                    message = row.message.orEmpty(),
                    modelId = targetModelId,
                    id = row.id,
                    linkedRoleIds = linkedRoleIds
                )
            }
        }
        // Unknown/unrecognized kinds: log and drop rather than silently applying them as generic
        // text, so data integrity issues surface instead of being hidden.
        else -> {
            logger.warn(
                "Dropping unrecognized instruction '{}' (type '{}') for role retrieval:"
                    + " unrecognized kind",
                row.name,
                row.type
            )
            null
        }
    }

    /**
     * Converts a domain [AgentInstruction] into a wire [AgentInstructionDto], resolving its message.
     *
     * The `when` covers all known domain subtypes; the `else` throws an `IllegalStateException`
     * for unknown subtypes, since encountering one indicates a programming error (a new subtype
     * was added without updating this mapping).
     *
     * @param instruction The domain instruction to convert.
     * @return The corresponding [AgentInstructionDto] with a resolved [AgentInstructionDto.message]
     *         and the shared-row identity fields.
     */
    private suspend fun toDtoInstruction(instruction: AgentInstruction): AgentInstructionDto {
        instruction.loadMessage()
        return when (instruction) {
            is SpawnableAgentsInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    name = instruction.name,
                    message = instruction.message,
                    id = instruction.id,
                    linkedRoleIds = instruction.linkedRoleIds
                )

            is RoleInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.ROLE,
                    name = instruction.name,
                    message = instruction.message,
                    id = instruction.id,
                    linkedRoleIds = instruction.linkedRoleIds
                )

            is MainInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.MAIN,
                    name = instruction.name,
                    message = instruction.message,
                    id = instruction.id,
                    linkedRoleIds = instruction.linkedRoleIds
                )

            is CustomInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.CUSTOM,
                    name = instruction.name,
                    message = instruction.message,
                    id = instruction.id,
                    linkedRoleIds = instruction.linkedRoleIds
                )

            is ModelSpecificInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.MODEL_SPECIFIC,
                    name = instruction.name,
                    message = instruction.message,
                    custom = buildJsonObject { put("modelId", instruction.modelId) },
                    id = instruction.id,
                    linkedRoleIds = instruction.linkedRoleIds
                )

            else -> error("Unknown AgentInstruction subtype: ${instruction::class.simpleName}")
        }
    }

    /**
     * Parses the raw `custom` JSON text of a stored instruction into an object.
     *
     * @param custom The stored JSON text, or null.
     * @return The parsed object, or null when absent or unparseable (dropped-row diagnostics then
     *         handle the inconsistency).
     */
    private fun parseCustom(custom: String?): JsonObject? = parseStoredCustom(json, custom)
}

/**
 * Parses the stored `custom` JSON text of an instruction row, tolerating malformed values.
 *
 * Shared by the read mapper and the request validator so a corrupt `custom` value degrades the same
 * way on both paths instead of raising a parse failure.
 *
 * @param json The codec used to parse the text.
 * @param custom The stored JSON text, or null.
 * @return The parsed object, or null when absent or unparseable.
 */
internal fun parseStoredCustom(json: Json, custom: String?): JsonObject? =
    runCatching { json.parseToJsonElement(custom.orEmpty()) }.getOrNull() as? JsonObject
