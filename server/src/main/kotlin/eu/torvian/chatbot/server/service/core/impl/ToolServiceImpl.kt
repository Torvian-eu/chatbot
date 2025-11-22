package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.DeleteToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.InsertToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.SetToolEnabledError as DaoSetToolEnabledError
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.UpdateToolDefinitionError
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.*
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of the [ToolService] interface.
 * Manages tool definitions and session-specific tool configurations.
 */
class ToolServiceImpl(
    private val toolDefinitionDao: ToolDefinitionDao,
    private val sessionToolConfigDao: SessionToolConfigDao,
    private val transactionScope: TransactionScope,
) : ToolService {

    private val logger: Logger = LogManager.getLogger(ToolServiceImpl::class.java)

    override suspend fun getAllTools(): List<ToolDefinition> {
        return transactionScope.transaction {
            toolDefinitionDao.getAllToolDefinitions()
        }
    }

    override suspend fun getToolById(id: Long): Either<GetToolError, ToolDefinition> =
        transactionScope.transaction {
            either {
                withError({ daoError: ToolDefinitionError.NotFound ->
                    GetToolError.ToolNotFound(daoError.id)
                }) {
                    toolDefinitionDao.getToolDefinitionById(id).bind()
                }
            }
        }

    override suspend fun createTool(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): Either<CreateToolError, ToolDefinition> = transactionScope.transaction {
        either {
            // Validate name
            ensure(name.isNotBlank()) {
                CreateToolError.InvalidName("Tool name cannot be blank.")
            }
            ensure(name.length <= 255) {
                CreateToolError.InvalidName("Tool name cannot exceed 255 characters.")
            }

            // Validate description
            ensure(description.isNotBlank()) {
                CreateToolError.InvalidDescription("Tool description cannot be blank.")
            }

            // Validate input schema has required JSON Schema properties
            ensure(inputSchema.containsKey("type") || inputSchema.containsKey("properties")) {
                CreateToolError.InvalidInputSchema(
                    "Input schema must be a valid JSON Schema with 'type' or 'properties'."
                )
            }

            // Check for duplicate name
            val existingTool = toolDefinitionDao.getToolDefinitionByName(name).getOrNull()
            ensure(existingTool == null) {
                CreateToolError.DuplicateName(name)
            }

            // Persist tool definition
            withError({ daoError: InsertToolDefinitionError ->
                when (daoError) {
                    is InsertToolDefinitionError.DuplicateName ->
                        CreateToolError.DuplicateName(daoError.name)
                    is InsertToolDefinitionError.InvalidSchema ->
                        CreateToolError.InvalidInputSchema(daoError.reason)
                }
            }) {
                toolDefinitionDao.insertToolDefinition(
                    name = name,
                    description = description,
                    type = type,
                    config = config,
                    inputSchema = inputSchema,
                    outputSchema = outputSchema,
                    isEnabled = isEnabled
                ).bind()
            }
        }
    }

    override suspend fun updateTool(tool: ToolDefinition): Either<UpdateToolError, Unit> =
        transactionScope.transaction {
            either {
                // Validate name
                ensure(tool.name.isNotBlank()) {
                    UpdateToolError.InvalidName("Tool name cannot be blank.")
                }
                ensure(tool.name.length <= 255) {
                    UpdateToolError.InvalidName("Tool name cannot exceed 255 characters.")
                }

                // Validate description
                ensure(tool.description.isNotBlank()) {
                    UpdateToolError.InvalidDescription("Tool description cannot be blank.")
                }

                // Validate input schema
                ensure(tool.inputSchema.containsKey("type") || tool.inputSchema.containsKey("properties")) {
                    UpdateToolError.InvalidInputSchema(
                        "Input schema must be a valid JSON Schema with 'type' or 'properties'."
                    )
                }

                // Check for duplicate name (excluding current tool)
                val existingTool = toolDefinitionDao.getToolDefinitionByName(tool.name).getOrNull()
                ensure(existingTool == null || existingTool.id == tool.id) {
                    UpdateToolError.DuplicateName(tool.name)
                }

                // Update tool definition
                withError({ daoError: UpdateToolDefinitionError ->
                    when (daoError) {
                        is UpdateToolDefinitionError.NotFound ->
                            UpdateToolError.ToolNotFound(daoError.id)
                        is UpdateToolDefinitionError.DuplicateName ->
                            UpdateToolError.DuplicateName(daoError.name)
                        is UpdateToolDefinitionError.InvalidSchema ->
                            UpdateToolError.InvalidInputSchema(daoError.reason)
                    }
                }) {
                    val updatedTool = tool.copy(updatedAt = Clock.System.now())
                    toolDefinitionDao.updateToolDefinition(updatedTool).bind()
                }
            }
        }

    override suspend fun deleteTool(id: Long): Either<DeleteToolError, Unit> =
        transactionScope.transaction {
            either {
                // Check if tool exists
                withError({ daoError: ToolDefinitionError.NotFound ->
                    DeleteToolError.ToolNotFound(daoError.id)
                }) {
                    toolDefinitionDao.getToolDefinitionById(id).bind()
                }

                // Check if tool is in use (has any session configurations)
                val sessionsUsingTool = sessionToolConfigDao.getSessionsUsingTool(id)
                ensure(sessionsUsingTool.isEmpty()) {
                    DeleteToolError.ToolInUse(
                        "Tool is currently configured in ${sessionsUsingTool.size} session(s) and cannot be deleted."
                    )
                }

                // Delete tool definition
                withError({ daoError: DeleteToolDefinitionError ->
                    when (daoError) {
                        is DeleteToolDefinitionError.NotFound ->
                            DeleteToolError.ToolNotFound(daoError.id)
                    }
                }) {
                    toolDefinitionDao.deleteToolDefinition(id).bind()
                }
            }
        }

    override suspend fun getEnabledToolsForSession(sessionId: Long): List<ToolDefinition> {
        return transactionScope.transaction {
            sessionToolConfigDao.getEnabledToolsForSession(sessionId)
        }
    }

    override suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolId: Long,
        enabled: Boolean
    ): Either<SetToolEnabledError, Unit> = transactionScope.transaction {
        either {
            // Verify tool exists
            withError({ _: ToolDefinitionError.NotFound ->
                SetToolEnabledError.ToolNotFound(toolId)
            }) {
                toolDefinitionDao.getToolDefinitionById(toolId).bind()
            }

            // Set tool enabled state for session
            withError({ daoError: DaoSetToolEnabledError ->
                when (daoError) {
                    is DaoSetToolEnabledError.ForeignKeyViolation -> {
                        // Try to determine if it's a session or tool issue
                        // Check if tool exists (we already did this above, so this shouldn't fail)
                        // This must be a session issue
                        SetToolEnabledError.SessionNotFound(sessionId)
                    }
                }
            }) {
                sessionToolConfigDao.setToolEnabledForSession(sessionId, toolId, enabled).bind()
            }

            logger.info("Tool $toolId ${if (enabled) "enabled" else "disabled"} for session $sessionId")
        }
    }
}

