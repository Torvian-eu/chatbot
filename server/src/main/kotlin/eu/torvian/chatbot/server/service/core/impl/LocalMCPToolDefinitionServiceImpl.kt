package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.InsertToolError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.service.core.LocalMCPToolDefinitionService
import eu.torvian.chatbot.server.service.core.RefreshResult
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.mcp.*
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [LocalMCPToolDefinitionService].
 * Coordinates between ToolDefinitionDao and LocalMCPToolDefinitionDao to maintain consistency.
 */
class LocalMCPToolDefinitionServiceImpl(
    private val toolDefinitionDao: ToolDefinitionDao,
    private val localMCPToolDefinitionDao: LocalMCPToolDefinitionDao,
    private val localMCPServerDao: LocalMCPServerDao,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope,
) : LocalMCPToolDefinitionService {

    private val logger: Logger = LogManager.getLogger(LocalMCPToolDefinitionServiceImpl::class.java)

    override suspend fun createMCPTools(
        serverId: Long,
        tools: List<LocalMCPToolDefinition>
    ): Either<CreateMCPToolsError, List<LocalMCPToolDefinition>> = transactionScope.transaction {
        either {
            // Validate that at least one tool is provided
            ensure(tools.isNotEmpty()) {
                CreateMCPToolsError.OtherError("At least one tool must be provided")
            }

            // All tools must have the same serverId
            ensure(tools.all { it.serverId == serverId }) {
                CreateMCPToolsError.OtherError("All tools must belong to the same server")
            }

            // Get existing tools for this server to check for duplicate names
            val existingTools = localMCPToolDefinitionDao.getToolsByServerId(serverId)

            val createdTools = mutableListOf<LocalMCPToolDefinition>()

            for (tool in tools) {
                // Check for duplicate name within server's tools
                ensure(!existingTools.any { it.name == tool.name }) {
                    CreateMCPToolsError.DuplicateName(tool.name)
                }
                ensure(!createdTools.any { it.name == tool.name }) {
                    CreateMCPToolsError.DuplicateName(tool.name)
                }

                // Create tool definition
                val createdToolDefinition =
                    withError({ toolServiceError: ValidateToolError ->
                        CreateMCPToolsError.ToolValidationError(toolServiceError)
                    }) {
                        toolService.createTool(
                            name = tool.name,
                            description = tool.description,
                            type = tool.type,
                            config = tool.config,
                            inputSchema = tool.inputSchema,
                            outputSchema = tool.outputSchema,
                            isEnabled = tool.isEnabled,
                        ).bind()
                    }

                // Create linkage
                withError({ daoError: InsertToolError ->
                    when (daoError) {
                        is InsertToolError.DuplicateLinkage ->
                            CreateMCPToolsError.OtherError("Linkage already exists for tool ${createdToolDefinition.id}")

                        is InsertToolError.ReferencedEntityNotFound ->
                            CreateMCPToolsError.OtherError("Referenced entity not found for tool ${createdToolDefinition.id}")
                    }
                }) {
                    localMCPToolDefinitionDao.insertTool(
                        toolDefinitionId = createdToolDefinition.id,
                        mcpServerId = serverId,
                        mcpToolName = tool.mcpToolName
                    ).bind()
                }

                // Create LocalMCPToolDefinition from the created tool definition
                createdTools.add(
                    tool.copy(
                        id = createdToolDefinition.id,
                        createdAt = createdToolDefinition.createdAt,
                        updatedAt = createdToolDefinition.updatedAt
                    )
                )
            }

            logger.info("Created ${createdTools.size} MCP tools for server $serverId")
            createdTools
        }
    }

    override suspend fun getMCPToolsByServerId(
        serverId: Long
    ): Either<GetMCPToolsByServerIdError, List<LocalMCPToolDefinition>> = transactionScope.transaction {
        either {
            // Validate server exists
            ensure(localMCPServerDao.existsById(serverId)) {
                GetMCPToolsByServerIdError.ServerNotFound(serverId)
            }

            localMCPToolDefinitionDao.getToolsByServerId(serverId)
        }
    }

    override suspend fun getMCPToolsForUser(userId: Long): List<LocalMCPToolDefinition> = transactionScope.transaction {
        localMCPToolDefinitionDao.getToolsForUser(userId)
    }

    override suspend fun getMCPToolById(
        toolId: Long
    ): Either<GetMCPToolByIdError, LocalMCPToolDefinition> = transactionScope.transaction {
        either {
            withError({ daoError: LocalMCPToolDefinitionError.NotFound ->
                GetMCPToolByIdError.ToolNotFound(daoError.toolDefinitionId)
            }) {
                localMCPToolDefinitionDao.getToolById(toolId).bind()
            }
        }
    }

    override suspend fun updateMCPTool(
        tool: LocalMCPToolDefinition
    ): Either<UpdateMCPToolError, LocalMCPToolDefinition> = transactionScope.transaction {
        either {
            // Get existing tools for this server to check for duplicate names
            val existingTools = localMCPToolDefinitionDao.getToolsByServerId(tool.serverId)

            // Validate tool exists for this server
            ensure(existingTools.any { it.id == tool.id }) {
                UpdateMCPToolError.ToolNotFound(tool.id)
            }

            // Check for duplicate name within server's tools (excluding current tool)
            ensure(!existingTools.any { it.name == tool.name && it.id != tool.id }) {
                UpdateMCPToolError.DuplicateName(tool.name)
            }
            // Update tool definition
            val updatedTool = withError({ error: UpdateToolError ->
                when (error) {
                    is UpdateToolError.ToolNotFound -> UpdateMCPToolError.ToolNotFound(error.id)
                    is UpdateToolError.ValidationError -> UpdateMCPToolError.ValidationError(error.error)
                }
            }) {
                toolService.updateTool(tool).bind() as LocalMCPToolDefinition
            }

            // Update linkage
            withError({ daoError: LocalMCPToolDefinitionError.NotFound ->
                UpdateMCPToolError.ToolNotFound(daoError.toolDefinitionId)
            }) {
                localMCPToolDefinitionDao.updateTool(
                    toolDefinitionId = tool.id,
                    mcpToolName = tool.mcpToolName
                ).bind()
            }

            logger.info("Updated MCP tool ${tool.id} for server ${tool.serverId}")

            updatedTool
        }
    }

    override suspend fun deleteMCPToolsForServer(
        serverId: Long
    ): Either<DeleteMCPToolsForServerError, Int> = transactionScope.transaction {
        either {
            // Validate server exists
            ensure(localMCPServerDao.existsById(serverId)) {
                DeleteMCPToolsForServerError.ServerNotFound(serverId)
            }

            val deletedCount = localMCPToolDefinitionDao.deleteToolsByServerId(serverId)

            logger.info("Deleted $deletedCount MCP tools for server $serverId")
            deletedCount
        }
    }

    override suspend fun refreshMCPTools(
        serverId: Long,
        currentTools: List<LocalMCPToolDefinition>
    ): Either<RefreshMCPToolsError, RefreshResult> = transactionScope.transaction {
        either {
            // Validate all tools have the correct serverId
            ensure(currentTools.all { it.serverId == serverId }) {
                RefreshMCPToolsError.OtherError("All tools must belong to server $serverId")
            }

            // Get existing tools for this server
            val existingTools = withError({ error: GetMCPToolsByServerIdError ->
                when (error) {
                    is GetMCPToolsByServerIdError.ServerNotFound ->
                        RefreshMCPToolsError.ServerNotFound(error.serverId)
                }
            }) {
                getMCPToolsByServerId(serverId).bind()
            }

            val existingToolsByName = existingTools.associateBy { it.mcpToolName }
            val currentToolsByName = currentTools.associateBy { it.mcpToolName }

            // Identify new tools (in current but not in existing)
            val newTools = currentTools.filter { it.mcpToolName !in existingToolsByName }

            // Create new tools, if any
            val createdTools = if (newTools.isEmpty()) {
                emptyList()
            } else {
                withError({ error: CreateMCPToolsError ->
                    when (error) {
                        is CreateMCPToolsError.OtherError ->
                            RefreshMCPToolsError.OtherError(error.message)

                        is CreateMCPToolsError.DuplicateName ->
                            RefreshMCPToolsError.DuplicateName(error.name)

                        is CreateMCPToolsError.ToolValidationError ->
                            RefreshMCPToolsError.ToolValidationError(error.validationError)
                    }
                }) {
                    createMCPTools(serverId, newTools).bind()
                }
            }

            // Identify changed tools (in both, but with different schema/description)
            val changedTools = currentTools.filter { current ->
                val existing = existingToolsByName[current.mcpToolName]
                existing != null && (
                        existing.description != current.description ||
                                existing.inputSchema != current.inputSchema ||
                                existing.outputSchema != current.outputSchema
                        )
            }

            // Update changed tools and collect the updated tool definitions
            val updatedTools = mutableListOf<LocalMCPToolDefinition>()
            for (tool in changedTools) {
                // Validate tool definition
                withError({ error: ValidateToolError ->
                    RefreshMCPToolsError.ToolValidationError(error)
                }) {
                    toolService.validateToolDefinition(tool).bind()
                }
                // Update tool definition
                val existing = existingToolsByName[tool.mcpToolName]!!
                val updatedTool = existing.copy(
                    description = tool.description,
                    inputSchema = tool.inputSchema,
                    outputSchema = tool.outputSchema,
                    updatedAt = Clock.System.now()
                )

                withError({ daoError: ToolDefinitionError.NotFound ->
                    RefreshMCPToolsError.OtherError("Tool not found: ${daoError.id}")
                }) {
                    toolDefinitionDao.updateToolDefinition(updatedTool).bind()
                }
                updatedTools.add(updatedTool)
            }

            // Identify removed tools (in existing but not in current)
            val removedTools = existingTools.filter { it.mcpToolName !in currentToolsByName }

            // Delete removed tools
            for (tool in removedTools) {
                withError({ daoError: ToolDefinitionError.NotFound ->
                    RefreshMCPToolsError.OtherError("Tool not found: ${daoError.id}")
                }) {
                    toolDefinitionDao.deleteToolDefinition(tool.id).bind()
                }
            }

            RefreshResult(
                addedTools = createdTools,
                updatedTools = updatedTools,
                deletedTools = removedTools
            ).also {
                logger.info("Refreshed MCP tools for server $serverId: added=${it.addedTools.size}, updated=${it.updatedTools.size}, deleted=${it.deletedTools.size}")
            }
        }
    }

    override suspend fun batchUpdateMCPTools(
        serverId: Long,
        toolDefinitions: List<LocalMCPToolDefinition>
    ): Either<BatchUpdateMCPToolsError, List<LocalMCPToolDefinition>> = transactionScope.transaction {
        either {
            // Validate that at least one tool is provided
            ensure(toolDefinitions.isNotEmpty()) {
                BatchUpdateMCPToolsError.OtherError("At least one tool must be provided")
            }

            // Validate that all tools belong to the specified server
            ensure(toolDefinitions.all { it.serverId == serverId }) {
                val invalidToolIds = toolDefinitions.filter { it.serverId != serverId }.map { it.id }
                BatchUpdateMCPToolsError.ToolsNotInServer(invalidToolIds)
            }

            // Validate that the server exists
            ensure(localMCPServerDao.existsById(serverId)) {
                BatchUpdateMCPToolsError.ServerNotFound(serverId)
            }

            // Get existing tools to validate they exist
            val existingTools = localMCPToolDefinitionDao.getToolsByServerId(serverId)
            val existingToolIds = existingTools.map { it.id }.toSet()
            val missingToolIds = toolDefinitions.map { it.id }.filter { it !in existingToolIds }
            ensure(missingToolIds.isEmpty()) {
                BatchUpdateMCPToolsError.ToolsNotFound(missingToolIds)
            }

            // Check for duplicate names within the batch
            val toolNames = toolDefinitions.map { it.name }
            val duplicateNames = toolNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            ensure(duplicateNames.isEmpty()) {
                BatchUpdateMCPToolsError.DuplicateName(duplicateNames.first())
            }

            // Check for duplicate names with other tools in the server
            val batchToolIds = toolDefinitions.map { it.id }.toSet()
            val otherServerTools = existingTools.filter { it.id !in batchToolIds }
            val otherServerToolNames = otherServerTools.map { it.name }.toSet()
            val conflictingName = toolNames.firstOrNull { it in otherServerToolNames }
            if (conflictingName != null) {
                raise(BatchUpdateMCPToolsError.DuplicateName(conflictingName))
            }

            // Perform batch update
            val updatedTools = mutableListOf<LocalMCPToolDefinition>()
            for (toolDefinition in toolDefinitions) {
                // Validate tool definition
                val updatedTool = withError({ error: UpdateToolError ->
                    when (error) {
                        is UpdateToolError.ToolNotFound -> BatchUpdateMCPToolsError.ToolsNotFound(listOf(error.id))
                        is UpdateToolError.ValidationError -> BatchUpdateMCPToolsError.ToolValidationError(error.error)
                    }
                }) {
                    toolService.updateTool(toolDefinition).bind()
                }
                updatedTools.add(updatedTool as LocalMCPToolDefinition)

                // Update linkage
                withError({ daoError: LocalMCPToolDefinitionError.NotFound ->
                    BatchUpdateMCPToolsError.ToolsNotFound(listOf(daoError.toolDefinitionId))
                }) {
                    localMCPToolDefinitionDao.updateTool(
                        toolDefinitionId = toolDefinition.id,
                        mcpToolName = toolDefinition.mcpToolName
                    ).bind()
                }
            }

            logger.info("Batch updated ${updatedTools.size} MCP tools for server $serverId")
            updatedTools
        }
    }
}
