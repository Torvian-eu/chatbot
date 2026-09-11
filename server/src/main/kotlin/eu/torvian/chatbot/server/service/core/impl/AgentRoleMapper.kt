package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.agent.AgentInstruction
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.agent.AgentRoleSummary
import eu.torvian.chatbot.server.service.core.agent.CustomInstruction
import eu.torvian.chatbot.server.service.core.agent.MainInstruction
import eu.torvian.chatbot.server.service.core.agent.ModelSpecificInstruction
import eu.torvian.chatbot.server.service.core.agent.RoleInstruction
import eu.torvian.chatbot.server.service.core.agent.SpawnableAgentsInstruction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Representation mapping for agent roles: stored row ↔ server domain ↔ wire DTO, plus the
 * instruction codec for the `instructions_json` column.
 *
 * Instructions are persisted as a raw JSON string (`instructions_json`) so serialization stays at
 * this service boundary: the stored shape equals the wire shape (polymorphic [AgentInstructionDto]
 * list), and the server domain [AgentInstruction] hierarchy is reconstructed per read. Callers
 * always supply the already-resolved relations (`tools`, `spawnableRoleIds`, `preset`, `disabled`);
 * the mapper never performs its own preset lookup, so the list path keeps its single batch read and
 * the write paths keep their validated-preset reuse without a second read.
 *
 * @property agentRoleDao DAO used by the dynamic target-summary loader of spawn allow-list
 *            instructions (owner-scoped role reads).
 * @property toolDefinitionDao DAO used by the `spawn_agent`-availability loader (tool reads scoped
 *            to the role's tool ids).
 * @property json Shared JSON codec used to (de)serialize the `instructions_json` column.
 */
internal class AgentRoleMapper(
    private val agentRoleDao: AgentRoleDao,
    private val toolDefinitionDao: ToolDefinitionDao,
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
     * @return Domain role with lazy instruction sources.
     */
    fun toDomain(
        entity: AgentRoleEntity,
        tools: Set<Long>,
        spawnableRoleIds: Set<Long>,
        projectId: Long?,
        preset: ModelPresetEntity?,
        ownerId: Long,
        disabled: Boolean
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
        instructions = decodeInstructions(entity.instructionsJson).mapNotNull {
            toDomainInstruction(
                dto = it,
                ownerId = ownerId,
                spawnableRoleIds = spawnableRoleIds,
                roleToolIds = tools
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
     * @return The corresponding [AgentRoleDto] with resolved instruction messages.
     */
    suspend fun toDto(
        entity: AgentRoleEntity,
        tools: Set<Long>,
        spawnableRoleIds: Set<Long>,
        projectId: Long?,
        preset: ModelPresetEntity?,
        ownerId: Long,
        disabled: Boolean
    ): AgentRoleDto = toDto(
        toDomain(
            entity = entity,
            tools = tools,
            spawnableRoleIds = spawnableRoleIds,
            projectId = projectId,
            preset = preset,
            ownerId = ownerId,
            disabled = disabled
        )
    )

    /**
     * Serializes an instruction DTO list into its JSON column representation (the wire shape).
     *
     * @param instructions The instruction DTOs.
     * @return The JSON array string.
     */
    fun encodeInstructions(instructions: List<AgentInstructionDto>): String = json.encodeToString(instructions)

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
     * Maps an [AgentInstructionDto] to its server domain subtype.
     *
     * Dispatches on the DTO's [AgentInstructionDto.type] string (a flat, non-polymorphic DTO).
     * The `else` branch logs a warning and returns null for unknown or unrecognized kinds,
     * so forward-compatible payloads don't silently apply unrecognized semantics.
     *
     * @param dto The DTO to map.
     * @param ownerId Owner scope for dynamic target-summary resolution.
     * @param spawnableRoleIds Unordered target ids used by the dynamic marker.
     * @param roleToolIds Tool ids used to determine whether `spawn_agent` is enabled.
     * @return The corresponding [AgentInstruction], or null when the kind is unrecognized or a
     *         `model_specific` instruction is missing its `modelId` in `custom` (both logged as
     *         warnings — they indicate a database inconsistency).
     */
    private fun toDomainInstruction(
        dto: AgentInstructionDto,
        ownerId: Long,
        spawnableRoleIds: Set<Long>,
        roleToolIds: Set<Long>
    ): AgentInstruction? = when (dto.type) {
        AgentInstructionTypes.SPAWNABLE_AGENTS -> SpawnableAgentsInstruction(
            name = dto.name,
            roleSummaryLoader = {
                // The allow-list is a set, so no persisted order exists; sort by name to keep the
                // generated prompt deterministic across reads.
                agentRoleDao.getRolesByIdsForUser(ownerId, spawnableRoleIds.toList())
                    .sortedBy { it.name.lowercase() }
                    .map { target ->
                        AgentRoleSummary(
                            id = target.id,
                            name = target.name,
                            displayName = target.displayName,
                            description = target.description
                        )
                    }
            },
            spawnAgentToolAvailableLoader = {
                toolDefinitionDao.getToolDefinitionsByIds(roleToolIds)
                    .any { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
            }
        )

        AgentInstructionTypes.ROLE -> RoleInstruction(dto.name, dto.message)
        AgentInstructionTypes.MAIN -> MainInstruction(dto.name, dto.message)
        AgentInstructionTypes.CUSTOM -> CustomInstruction(dto.name, dto.message)
        AgentInstructionTypes.MODEL_SPECIFIC -> {
            val targetModelId = dto.modelSpecificId()
            if (targetModelId == null) {
                // A model_specific instruction without a modelId is a data integrity issue: the
                // stored JSON was malformed or partially migrated. Log it and drop the instruction
                // rather than crashing role retrieval.
                logger.warn(
                    "Dropping model_specific instruction '{}' for role retrieval: missing 'modelId' in custom",
                    dto.name
                )
                null
            } else {
                ModelSpecificInstruction(
                    name = dto.name,
                    message = dto.message,
                    modelId = targetModelId
                )
            }
        }
        // Unknown/unrecognized kinds: log and drop rather than silently applying them as generic
        // text, so data integrity issues surface instead of being hidden.
        else -> {
            logger.warn(
                "Dropping unrecognized instruction '{}' (type '{}') for role retrieval:"
                + " unrecognized kind",
                dto.name,
                dto.type
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
     * @return The corresponding [AgentInstructionDto] with a resolved [AgentInstructionDto.message].
     */
    private suspend fun toDtoInstruction(instruction: AgentInstruction): AgentInstructionDto {
        instruction.loadMessage()
        return when (instruction) {
            is SpawnableAgentsInstruction ->
                AgentInstructionDto(AgentInstructionTypes.SPAWNABLE_AGENTS, instruction.name, instruction.message)
            is RoleInstruction ->
                AgentInstructionDto(AgentInstructionTypes.ROLE, instruction.name, instruction.message)
            is MainInstruction ->
                AgentInstructionDto(AgentInstructionTypes.MAIN, instruction.name, instruction.message)
            is CustomInstruction ->
                AgentInstructionDto(AgentInstructionTypes.CUSTOM, instruction.name, instruction.message)
            is ModelSpecificInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.MODEL_SPECIFIC,
                    name = instruction.name,
                    message = instruction.message,
                    custom = buildJsonObject { put("modelId", instruction.modelId) }
                )

            else -> error("Unknown AgentInstruction subtype: ${instruction::class.simpleName}")
        }
    }

    /**
     * Deserializes the `instructions_json` column into the instruction DTO list (the wire shape).
     *
     * @param instructionsJson The JSON array string.
     * @return The instruction DTOs; an empty list when the stored value is unparseable.
     */
    private fun decodeInstructions(instructionsJson: String): List<AgentInstructionDto> =
        runCatching { json.decodeFromString<List<AgentInstructionDto>>(instructionsJson) }.getOrDefault(emptyList())
}
