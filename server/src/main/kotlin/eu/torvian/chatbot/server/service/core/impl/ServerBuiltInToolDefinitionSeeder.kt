package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError
import eu.torvian.chatbot.server.data.tables.ServerBuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.UsersTable
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.DeleteToolError
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError
import eu.torvian.chatbot.server.service.setup.DataInitializer
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Seeds the default server built-in tool definitions (one instance per [ServerBuiltInToolCatalog]
 * spec per user).
 *
 * Server built-in tools are per-user instances: every user owns their own base `tool_definitions`
 * row for each [ServerBuiltInToolCatalog] spec, linked through the
 * `server_builtin_tool_definitions` side table. Seeding is idempotent — an existing row for the
 * same user + spec name is left untouched, so user-edited description/enabled flags survive
 * repeated calls.
 *
 * The class doubles as a [DataInitializer] that reconciles **all** users at startup (existing users
 * get their instances retroactively), and [ensureForUser] is also invoked right after user
 * registration so new users get their instances immediately.
 *
 * @property serverBuiltInToolDefinitionDao DAO for the server built-in linkage table.
 * @property toolService Service used to create the base tool definition rows.
 * @property transactionScope Transaction boundary used to make seeding atomic.
 */
class ServerBuiltInToolDefinitionSeeder(
    private val serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope
) : DataInitializer {

    private val logger: Logger = LogManager.getLogger(ServerBuiltInToolDefinitionSeeder::class.java)

    override val name: String = "Server Built-In Tools"

    /**
     * Reports whether every existing user already owns a full set of server built-in tools.
     *
     * This is only a startup short-circuit; individual [ensureForUser] calls remain idempotent
     * regardless. An empty user base counts as initialized (there is nothing to seed).
     */
    override suspend fun isInitialized(): Boolean = transactionScope.transaction {
        val userIds = UsersTable.selectAll().map { it[UsersTable.id].value }
        if (userIds.isEmpty()) {
            return@transaction true
        }
        val neededPerUser = ServerBuiltInToolCatalog.allTools.size
        val rowsByUser = ServerBuiltInToolDefinitionTable
            .selectAll()
            .groupBy { it[ServerBuiltInToolDefinitionTable.userId].value }
        userIds.all { userId -> (rowsByUser[userId]?.size ?: 0) >= neededPerUser }
    }

    /**
     * Seeds server built-in tools for every existing user.
     *
     * The operation is idempotent: users that already own all catalog instances are left untouched.
     */
    override suspend fun initialize(): Either<String, Unit> = transactionScope.transaction {
        either {
            val userIds = UsersTable.selectAll().map { it[UsersTable.id].value }
            for (userId in userIds) {
                withError({ error: SeedServerBuiltInToolsError ->
                    "Failed to seed server built-in tools for user $userId: $error"
                }) {
                    ensureForUser(userId).bind()
                }
            }
        }
    }

    /**
     * Seeds the server built-in tool instances for one user, creating any catalog spec that is
     * missing.
     *
     * Existing linkages (same user + catalog name) are left untouched, so repeated calls and
     * user edits are safe. The real per-tool [ServerBuiltInToolCatalog.ServerBuiltInToolSpec.inputSchema]
     * is persisted so the LLM can discover and call the tool.
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedServerBuiltInToolsError] or the list of [ServerBuiltInToolDefinition] now
     *         owned by the user.
     */
    suspend fun ensureForUser(userId: Long): Either<SeedServerBuiltInToolsError, List<ServerBuiltInToolDefinition>> =
        transactionScope.transaction {
            either {
                val existing = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
                    .associateBy { it.name }

                for (spec in ServerBuiltInToolCatalog.allTools) {
                    if (existing.containsKey(spec.name)) continue

                    val created = withError({ error: ValidateToolError ->
                        SeedServerBuiltInToolsError.ToolCreationFailed(error)
                    }) {
                        toolService.createTool(
                            name = spec.name,
                            description = spec.description,
                            type = ToolType.BUILTIN_SERVER,
                            config = JsonObject(emptyMap()),
                            inputSchema = spec.inputSchema,
                            outputSchema = null,
                            // Newly seeded server built-in tools are enabled by default.
                            isEnabled = true
                        ).bind()
                    }

                    withError({ error: ServerBuiltInToolDefinitionError ->
                        SeedServerBuiltInToolsError.LinkageFailed(error)
                    }) {
                        serverBuiltInToolDefinitionDao.insertTool(
                            toolDefinitionId = created.id,
                            userId = userId
                        ).bind()
                    }
                }

                if (existing.size < ServerBuiltInToolCatalog.allTools.size) {
                    logger.info("Seeded server built-in tools for user {}", userId)
                }
                serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
            }
        }

    /**
     * Reconciles a user's server built-in tool definitions with the current catalog.
     *
     * For every [ServerBuiltInToolCatalog] spec: if the user has no instance for that spec name, it
     * is created (idempotent, like [ensureForUser]). If an instance already exists, its description
     * and input schema are overwritten with the catalog values, while the enabled flag and approval
     * preferences are preserved. Instances whose catalog spec no longer exists are pruned (the base
     * definition and its linkage are deleted). This is the server-side counterpart of the "Reset to
     * defaults" action and is used to bring existing users up to date after catalog definitions
     * change.
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedServerBuiltInToolsError] or the list of [ServerBuiltInToolDefinition]
     *         now owned by the user.
     */
    suspend fun resetToDefaults(userId: Long): Either<SeedServerBuiltInToolsError, List<ServerBuiltInToolDefinition>> =
        transactionScope.transaction {
            either {
                val existing = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
                    .associateBy { it.name }

                for (spec in ServerBuiltInToolCatalog.allTools) {
                    val current = existing[spec.name]
                    if (current == null) {
                        // Missing tool: create it exactly like seeding does, enabled by default.
                        val created = withError({ error: ValidateToolError ->
                            SeedServerBuiltInToolsError.ToolCreationFailed(error)
                        }) {
                            toolService.createTool(
                                name = spec.name,
                                description = spec.description,
                                type = ToolType.BUILTIN_SERVER,
                                config = JsonObject(emptyMap()),
                                inputSchema = spec.inputSchema,
                                outputSchema = null,
                                isEnabled = true
                            ).bind()
                        }

                        withError({ error: ServerBuiltInToolDefinitionError ->
                            SeedServerBuiltInToolsError.LinkageFailed(error)
                        }) {
                            serverBuiltInToolDefinitionDao.insertTool(
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
                                    SeedServerBuiltInToolsError.LinkageFailed(
                                        ServerBuiltInToolDefinitionError.ReferencedEntityNotFound(
                                            toolDefinitionId = current.id,
                                            userId = userId,
                                            message = "Tool definition not found during reset"
                                        )
                                    )

                                is UpdateToolError.ValidationError ->
                                    SeedServerBuiltInToolsError.ToolCreationFailed(error.error)
                            }
                        }) {
                            toolService.updateTool(repaired).bind()
                        }
                    }
                    // else: already in sync, nothing to do.
                }

                // Prune stale rows: a future catalog version may remove a spec, and its per-user
                // instances must not linger (they would still appear in the user's tool set and stay
                // attachable to roles). Deleting the base definition via the service removes the
                // linkage row and approval preferences through the FK ON DELETE CASCADE.
                val catalogNames = ServerBuiltInToolCatalog.allTools.map { it.name }.toSet()
                for (stale in existing.values.filterNot { it.name in catalogNames }) {
                    withError({ error: DeleteToolError ->
                        SeedServerBuiltInToolsError.ToolDeletionFailed(error)
                    }) {
                        toolService.deleteTool(stale.id).bind()
                    }
                    logger.info("Pruned stale server built-in tool {} (user {})", stale.id, userId)
                }

                logger.info("Reset server built-in tools to defaults for user {}", userId)
                serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
            }
        }
}
