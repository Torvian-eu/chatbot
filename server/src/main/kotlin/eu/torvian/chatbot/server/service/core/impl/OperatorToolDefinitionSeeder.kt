package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.OperatorToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError
import eu.torvian.chatbot.server.data.tables.OperatorToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.UsersTable
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.SeedOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError
import eu.torvian.chatbot.server.service.setup.DataInitializer
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Seeds the default operator tool definitions (one `spawn_agent` row per user).
 *
 * Operator tools are per-user instances: every user owns their own base `tool_definitions` row for
 * each [OperatorToolCatalog] spec, linked through the `operator_tool_definitions` side table. Seeding
 * is idempotent — an existing row for the same user + spec name is left untouched, so user-edited
 * name/description/enabled flags survive repeated calls.
 *
 * The class doubles as a [DataInitializer] that reconciles **all** users at startup (existing users
 * get their instances retroactively), and [ensureForUser] is also invoked right after user
 * registration so new users get their instances immediately.
 *
 * @property operatorToolDefinitionDao DAO for the operator linkage table.
 * @property toolService Service used to create the base tool definition rows.
 * @property transactionScope Transaction boundary used to make seeding atomic.
 */
class OperatorToolDefinitionSeeder(
    private val operatorToolDefinitionDao: OperatorToolDefinitionDao,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope
) : DataInitializer {

    private val logger: Logger = LogManager.getLogger(OperatorToolDefinitionSeeder::class.java)

    override val name: String = "Operator Tools"

    /**
     * Reports whether every existing user already owns a full set of operator tools.
     *
     * This is only a startup short-circuit; individual [ensureForUser] calls remain idempotent
     * regardless. An empty user base counts as initialized (there is nothing to seed).
     */
    override suspend fun isInitialized(): Boolean = transactionScope.transaction {
        val userIds = UsersTable.selectAll().map { it[UsersTable.id].value }
        if (userIds.isEmpty()) {
            return@transaction true
        }
        val neededPerUser = OperatorToolCatalog.allTools.size
        val rowsByUser = OperatorToolDefinitionTable
            .selectAll()
            .groupBy { it[OperatorToolDefinitionTable.userId].value }
        userIds.all { userId -> (rowsByUser[userId]?.size ?: 0) >= neededPerUser }
    }

    /**
     * Seeds operator tools for every existing user.
     *
     * The operation is idempotent: users that already own all catalog instances are left untouched.
     */
    override suspend fun initialize(): Either<String, Unit> = transactionScope.transaction {
        either {
            val userIds = UsersTable.selectAll().map { it[UsersTable.id].value }
            for (userId in userIds) {
                withError({ error: SeedOperatorToolsError ->
                    "Failed to seed operator tools for user $userId: $error"
                }) {
                    ensureForUser(userId).bind()
                }
            }
        }
    }

    /**
     * Seeds the operator tool instances for one user, creating any catalog spec that is missing.
     *
     * Existing linkages (same user + catalog name) are left untouched, so repeated calls and
     * user edits are safe. The real per-tool [OperatorToolCatalog.OperatorToolSpec.inputSchema] is
     * persisted so the LLM can discover and call the tool.
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedOperatorToolsError] or the list of [OperatorToolDefinition] now owned by
     *         the user.
     */
    suspend fun ensureForUser(userId: Long): Either<SeedOperatorToolsError, List<OperatorToolDefinition>> =
        transactionScope.transaction {
            either {
                val existing = operatorToolDefinitionDao.getToolsByUserId(userId)
                    .associateBy { it.name }

                for (spec in OperatorToolCatalog.allTools) {
                    if (existing.containsKey(spec.name)) continue

                    val created = withError({ error: ValidateToolError ->
                        SeedOperatorToolsError.ToolCreationFailed(error)
                    }) {
                        toolService.createTool(
                            name = spec.name,
                            description = spec.description,
                            type = ToolType.OPERATOR,
                            config = JsonObject(emptyMap()),
                            inputSchema = spec.inputSchema,
                            outputSchema = null,
                            // Newly seeded operator tools are enabled by default.
                            isEnabled = true
                        ).bind()
                    }

                    withError({ error: OperatorToolDefinitionError ->
                        SeedOperatorToolsError.LinkageFailed(error)
                    }) {
                        operatorToolDefinitionDao.insertTool(
                            toolDefinitionId = created.id,
                            userId = userId
                        ).bind()
                    }
                }

                if (existing.size < OperatorToolCatalog.allTools.size) {
                    logger.info("Seeded operator tools for user {}", userId)
                }
                operatorToolDefinitionDao.getToolsByUserId(userId)
            }
        }

    /**
     * Reconciles a user's operator tool definitions with the current catalog.
     *
     * For every [OperatorToolCatalog] spec: if the user has no instance for that spec name, it is
     * created (idempotent, like [ensureForUser]). If an instance already exists, its description
     * and input schema are overwritten with the catalog values, while the enabled flag and approval
     * preferences are preserved. This is the server-side counterpart of the "Reset to defaults"
     * action in the Operator Tools settings tab and is used to bring existing users up to date after
     * catalog definitions change.
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedOperatorToolsError] or the list of [OperatorToolDefinition] now owned by
     *         the user.
     */
    suspend fun resetToDefaults(userId: Long): Either<SeedOperatorToolsError, List<OperatorToolDefinition>> =
        transactionScope.transaction {
            either {
                val existing = operatorToolDefinitionDao.getToolsByUserId(userId)
                    .associateBy { it.name }

                for (spec in OperatorToolCatalog.allTools) {
                    val current = existing[spec.name]
                    if (current == null) {
                        // Missing tool: create it exactly like seeding does, enabled by default.
                        val created = withError({ error: ValidateToolError ->
                            SeedOperatorToolsError.ToolCreationFailed(error)
                        }) {
                            toolService.createTool(
                                name = spec.name,
                                description = spec.description,
                                type = ToolType.OPERATOR,
                                config = JsonObject(emptyMap()),
                                inputSchema = spec.inputSchema,
                                outputSchema = null,
                                isEnabled = true
                            ).bind()
                        }

                        withError({ error: OperatorToolDefinitionError ->
                            SeedOperatorToolsError.LinkageFailed(error)
                        }) {
                            operatorToolDefinitionDao.insertTool(
                                toolDefinitionId = created.id,
                                userId = userId
                            ).bind()
                        }
                    } else if (current.description != spec.description ||
                        current.inputSchema != spec.inputSchema
                    ) {
                        // Existing tool: repair only the catalog-derived fields. The enabled flag and
                        // approval preferences are intentionally preserved so a user's choices survive
                        // a reset.
                        val repaired = current.copy(
                            description = spec.description,
                            inputSchema = spec.inputSchema
                        )
                        withError({ error: UpdateToolError ->
                            when (error) {
                                is UpdateToolError.ToolNotFound ->
                                    SeedOperatorToolsError.LinkageFailed(
                                        OperatorToolDefinitionError.ReferencedEntityNotFound(
                                            toolDefinitionId = current.id,
                                            userId = userId,
                                            message = "Tool definition not found during reset"
                                        )
                                    )

                                is UpdateToolError.ValidationError ->
                                    SeedOperatorToolsError.ToolCreationFailed(error.error)
                            }
                        }) {
                            toolService.updateTool(repaired).bind()
                        }
                    }
                    // else: already in sync, nothing to do.
                }

                logger.info("Reset operator tools to defaults for user {}", userId)
                operatorToolDefinitionDao.getToolsByUserId(userId)
            }
        }
}
