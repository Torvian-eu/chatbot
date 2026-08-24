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
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver
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
 * `server_builtin_tool_definitions` side table. The public [ServerBuiltInToolDefinition.name] is
 * the user's effective tool-name prefix concatenated to the canonical catalog name (see
 * [buildPublicToolName]); the canonical, unprefixed name is persisted as
 * `ServerBuiltInToolDefinition.builtInToolName` and is the stable identity used for deduplication,
 * reconciliation, and execution dispatch across prefix changes.
 *
 * Seeding is idempotent — an existing row for the same user + canonical name is left untouched, so
 * user-edited description/enabled flags survive repeated calls. The class doubles as a
 * [DataInitializer] that reconciles **all** users at startup (existing users get their instances
 * retroactively, prefix drift is renamed, and stale rows are pruned) **without** touching
 * user-edited descriptions or input schemas — the full catalog repair lives exclusively in
 * [resetToDefaults] (the explicit reset action). [ensureForUser] is also invoked right after user
 * registration so new users get their instances immediately.
 *
 * @property serverBuiltInToolDefinitionDao DAO for the server built-in linkage table.
 * @property toolService Service used to create the base tool definition rows.
 * @property transactionScope Transaction boundary used to make seeding atomic.
 * @property prefixResolver Resolves the effective per-user prefix inside every seeding/reconcile
 *            operation.
 */
class ServerBuiltInToolDefinitionSeeder(
    private val serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope,
    private val prefixResolver: ServerBuiltInToolNamePrefixResolver
) : DataInitializer {

    private val logger: Logger = LogManager.getLogger(ServerBuiltInToolDefinitionSeeder::class.java)

    override val name: String = "Server Built-In Tools"

    /**
     * Joins a user's tool-name prefix to a canonical catalog name **without** inserting a dot.
     *
     * Some LLM providers reject dots in tool names, so the prefix is concatenated directly to the
     * canonical name without any separator added by the server (e.g. `"chatbot-"` +
     * `"list_agent_roles"` → `"chatbot-list_agent_roles"`). A blank prefix means no prefix, so the
     * canonical name is returned unchanged. The unprefixed [ServerBuiltInToolDefinition.builtInToolName]
     * is always persisted separately and is the value the executor uses to dispatch, so the public
     * name format does not affect execution.
     *
     * @param prefix The effective user prefix (may be blank = no prefix).
     * @param builtInToolName Canonical, unprefixed catalog name.
     * @return The public tool name.
     */
    private fun buildPublicToolName(prefix: String, builtInToolName: String): String =
        if (prefix.isBlank()) builtInToolName else "$prefix$builtInToolName"

    /**
     * Reports whether every existing user already owns a full, correctly-prefixed server built-in
     * tool set.
     *
     * This is a startup short-circuit: per user it requires (a) every catalog name present among
     * the user's linkages (keyed by the canonical `builtInToolName`) and (b) every linkage's public
     * name equal to `prefix + canonical` for the user's effective prefix. An empty user base counts
     * as initialized. When false, [initialize] runs the full per-user reconcile path.
     */
    override suspend fun isInitialized(): Boolean = transactionScope.transaction {
        val userIds = UsersTable.selectAll().map { it[UsersTable.id].value }
        if (userIds.isEmpty()) {
            return@transaction true
        }
        val catalogNames = ServerBuiltInToolCatalog.allTools.map { it.name }.toSet()
        userIds.all { userId ->
            val prefix = prefixResolver.resolvePrefix(userId)
            val byCanonical = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
                .associateBy { it.builtInToolName }
            val allCatalogNamesPresent = catalogNames.all { it in byCanonical }
            val allNamesCorrect = byCanonical.values.all { tool ->
                tool.name == buildPublicToolName(prefix, tool.builtInToolName)
            }
            allCatalogNamesPresent && allNamesCorrect
        }
    }

    /**
     * Reconciles server built-in tools for every existing user.
     *
     * Runs a name-only reconcile per user: missing instances are created, public names that drifted
     * from the user's effective prefix are renamed, and stale rows whose canonical spec no longer
     * exists in the catalog are pruned. User-edited descriptions and input schemas are intentionally
     * left untouched — the full catalog repair lives exclusively in [resetToDefaults] (the explicit
     * "Reset to defaults" action), so a startup after a prefix or catalog change cannot silently
     * overwrite user customization. A failure for one user aborts the whole initializer with a
     * descriptive message.
     */
    override suspend fun initialize(): Either<String, Unit> = transactionScope.transaction {
        either {
            val userIds = UsersTable.selectAll().map { it[UsersTable.id].value }
            for (userId in userIds) {
                withError({ error: SeedServerBuiltInToolsError ->
                    "Failed to seed server built-in tools for user $userId: $error"
                }) {
                    reconcilePublicNamesOnly(userId).bind()
                }
            }
        }
    }

    /**
     * Performs the startup name-only reconcile for one user.
     *
     * The startup path deliberately repairs only the seeder-owned public names: it creates missing
     * instances (via [ensureForUser]), renames any public name that drifted from the user's effective
     * prefix (via [renamePublicNamesForPrefix]), and prunes stale rows whose canonical spec is no
     * longer in the catalog. Descriptions and input schemas are never rewritten here — unlike
     * [resetToDefaults] — so user customization survives every startup, including the first one
     * after a prefix change (where every existing user's names drift from the new effective prefix).
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedServerBuiltInToolsError] or Unit on success.
     */
    private suspend fun reconcilePublicNamesOnly(userId: Long): Either<SeedServerBuiltInToolsError, Unit> =
        transactionScope.transaction {
            either {
                // Create any catalog spec the user does not own yet; new rows use the effective prefix.
                ensureForUser(userId).bind()
                // Repair name drift to the current effective prefix (no-op when names already match).
                renamePublicNamesForPrefix(userId, prefixResolver.resolvePrefix(userId)).bind()
                // Prune stale rows: a future catalog version may remove a spec, and its per-user
                // instances must not linger (they would still appear in the user's tool set and stay
                // attachable to roles). Deleting the base definition via the service removes the
                // linkage row and approval preferences through the FK ON DELETE CASCADE.
                val catalogNames = ServerBuiltInToolCatalog.allTools.map { it.name }.toSet()
                val stale = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
                    .filterNot { it.builtInToolName in catalogNames }
                for (tool in stale) {
                    withError({ error: DeleteToolError ->
                        SeedServerBuiltInToolsError.ToolDeletionFailed(error)
                    }) {
                        toolService.deleteTool(tool.id).bind()
                    }
                    logger.info("Pruned stale server built-in tool {} (user {})", tool.id, userId)
                }
            }
        }

    /**
     * Seeds the server built-in tool instances for one user, creating any catalog spec that is
     * missing.
     *
     * Existing linkages (same user + canonical `builtInToolName`) are left untouched, so repeated
     * calls and user edits are safe. Newly created instances use the user's effective prefix for
     * their public name. The real per-tool [ServerBuiltInToolCatalog.ServerBuiltInToolSpec.inputSchema]
     * is persisted so the LLM can discover and call the tool.
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedServerBuiltInToolsError] or the list of [ServerBuiltInToolDefinition] now
     *         owned by the user.
     */
    suspend fun ensureForUser(userId: Long): Either<SeedServerBuiltInToolsError, List<ServerBuiltInToolDefinition>> =
        transactionScope.transaction {
            either {
                val prefix = prefixResolver.resolvePrefix(userId)
                val existing = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
                    .associateBy { it.builtInToolName }

                for (spec in ServerBuiltInToolCatalog.allTools) {
                    val canonicalName = spec.name
                    if (existing.containsKey(canonicalName)) continue

                    val created = withError({ error: ValidateToolError ->
                        SeedServerBuiltInToolsError.ToolCreationFailed(error)
                    }) {
                        toolService.createTool(
                            name = buildPublicToolName(prefix, canonicalName),
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
                            userId = userId,
                            builtInToolName = canonicalName
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
     * Renames the public names of a user's server built-in tools to reflect a new prefix.
     *
     * Only the public [ServerBuiltInToolDefinition.name] is changed; the canonical
     * [ServerBuiltInToolDefinition.builtInToolName] is preserved. When [newPrefix] is blank, the
     * public names revert to the canonical names. Used by the prefix service so the preference
     * write and the rename commit atomically.
     *
     * @param userId Owning user identifier.
     * @param newPrefix New effective prefix (blank = no prefix).
     * @return Either a [SeedServerBuiltInToolsError] or Unit on success.
     */
    suspend fun renamePublicNamesForPrefix(
        userId: Long,
        newPrefix: String
    ): Either<SeedServerBuiltInToolsError, Unit> = transactionScope.transaction {
        either {
            val tools = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
            for (tool in tools) {
                val publicName = buildPublicToolName(newPrefix, tool.builtInToolName)
                if (publicName != tool.name) {
                    withError({ error: ServerBuiltInToolDefinitionError ->
                        SeedServerBuiltInToolsError.LinkageFailed(error)
                    }) {
                        serverBuiltInToolDefinitionDao.updatePublicName(tool.id, publicName).bind()
                    }
                }
            }
            logger.info("Renamed server built-in tool public names for user {} (prefix='{}')", userId, newPrefix)
        }
    }

    /**
     * Reconciles a user's server built-in tool definitions with the current catalog.
     *
     * For every [ServerBuiltInToolCatalog] spec: if the user has no instance for that canonical
     * name, it is created (idempotent, like [ensureForUser]) with the user's effective prefix. If an
     * instance already exists, its public name (prefix-aware), description, and input schema are
     * overwritten with the catalog values whenever any of them differs, while the enabled flag and
     * approval preferences are preserved. Instances whose canonical name no longer exists in the
     * catalog are pruned (the base definition and its linkage are deleted). This is the server-side
     * counterpart of the "Reset to defaults" action and is used to bring existing users up to date
     * after catalog or prefix changes.
     *
     * @param userId Owning user identifier.
     * @return Either a [SeedServerBuiltInToolsError] or the list of [ServerBuiltInToolDefinition]
     *         now owned by the user.
     */
    suspend fun resetToDefaults(userId: Long): Either<SeedServerBuiltInToolsError, List<ServerBuiltInToolDefinition>> =
        transactionScope.transaction {
            either {
                val prefix = prefixResolver.resolvePrefix(userId)
                val existing = serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
                    .associateBy { it.builtInToolName }

                for (spec in ServerBuiltInToolCatalog.allTools) {
                    val canonicalName = spec.name
                    val expectedPublicName = buildPublicToolName(prefix, canonicalName)

                    val current = existing[canonicalName]
                    if (current == null) {
                        // Missing tool: create it exactly like seeding does, enabled by default.
                        val created = withError({ error: ValidateToolError ->
                            SeedServerBuiltInToolsError.ToolCreationFailed(error)
                        }) {
                            toolService.createTool(
                                name = expectedPublicName,
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
                                userId = userId,
                                builtInToolName = canonicalName
                            ).bind()
                        }
                    } else if (current.name != expectedPublicName ||
                        current.description != spec.description ||
                        current.inputSchema != spec.inputSchema
                    ) {
                        // Existing tool: repair only the catalog-derived fields. The enabled flag and
                        // approval preferences are intentionally preserved so a user's choices survive
                        // a reset. The public name is re-applied only when it actually differs (e.g.
                        // after a prefix change), avoiding needless writes for pre-existing tools.
                        val repaired = current.copy(
                            name = expectedPublicName,
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
                for (stale in existing.values.filterNot { it.builtInToolName in catalogNames }) {
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
