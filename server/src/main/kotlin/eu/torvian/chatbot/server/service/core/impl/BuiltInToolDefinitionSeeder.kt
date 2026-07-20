package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.BuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.BuiltInToolDefinitionError
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.SeedBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Seeds the default built-in worker tool definitions for a worker.
 *
 * The eight built-in tools are registered once per worker. Seeding is idempotent: a tool is only
 * created when no linkage exists for the same worker + unprefixed `builtInToolName`. The public
 * [BuiltInWorkerToolDefinition.name] is derived from the worker's configured prefix (when present),
 * while [BuiltInWorkerToolDefinition.builtInToolName] always stores the unprefixed canonical name.
 *
 * The prefix is concatenated to the canonical name **without** a dot (see [buildPublicToolName]),
 * because some LLM providers reject dots in tool names and would otherwise fail the tool call.
 *
 * @property toolDefinitionDao DAO for the built-in linkage table.
 * @property toolService Service used to create the base tool definition rows.
 * @property transactionScope Transaction boundary used to make seeding atomic.
 */
class BuiltInToolDefinitionSeeder(
    private val toolDefinitionDao: BuiltInToolDefinitionDao,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope
) {
    private val logger: Logger = LogManager.getLogger(BuiltInToolDefinitionSeeder::class.java)

    /**
     * Canonical built-in tool specifications, in stable catalog order. The [BuiltInToolCatalog] is
     * the single source of truth shared with the worker implementations.
     */
    private val defaultToolSpecs: List<BuiltInToolCatalog.BuiltInToolSpec> = BuiltInToolCatalog.allTools

    /**
     * Joins a worker tool-name prefix to a canonical built-in tool name **without** inserting a dot.
     *
     * Some LLM providers reject dots in tool names, so the prefix is concatenated directly to the
     * canonical name without any separator added by the server. The caller supplies the separator
     * as part of the prefix (e.g. `"project1_"` + `"read_text_file"` → `"project1_read_text_file"`,
     * or `"proj-"` + `"read_text_file"` → `"proj-read_text_file"`). The unprefixed [builtInToolName]
     * is always persisted separately and is the value the worker uses to resolve and execute the
     * tool, so the public name format does not affect dispatch.
     *
     * @param prefix Optional worker-configured tool prefix (e.g. "project1_").
     * @param builtInToolName Unprefixed canonical tool name (e.g. "read_text_file").
     * @return Prefixed name when [prefix] is not blank; otherwise [builtInToolName].
     */
    private fun buildPublicToolName(prefix: String?, builtInToolName: String): String =
        if (prefix.isNullOrBlank()) builtInToolName else "$prefix$builtInToolName"

    /**
     * Seeds the default built-in tools for the given worker.
     *
     * Existing linkages (same worker + unprefixed name) are left untouched, so repeated calls are
     * safe. The public name of each seeded tool is prefixed with [toolNamePrefix] when it is
     * non-blank. The real per-tool [BuiltInToolCatalog.BuiltInToolSpec.inputSchema] is persisted so
     * the LLM can discover and call the tool.
     *
     * @param workerId Owning worker identifier.
     * @param toolNamePrefix Optional prefix applied to public tool names (e.g. "project1_").
     * @return Either a [SeedBuiltInToolsError] or the list of [BuiltInWorkerToolDefinition] now owned by the worker.
     */
    suspend fun seedDefaultToolsForWorker(
        workerId: Long,
        toolNamePrefix: String?
    ): Either<SeedBuiltInToolsError, List<BuiltInWorkerToolDefinition>> = transactionScope.transaction {
        either {
            val existing = toolDefinitionDao.getToolsByWorkerId(workerId)
                .associateBy { it.builtInToolName }
            val prefix = toolNamePrefix?.takeIf { it.isNotBlank() }

            for (spec in defaultToolSpecs) {
                val name = spec.builtInToolName
                if (existing.containsKey(name)) continue

                val publicName = buildPublicToolName(prefix, name)
                val created = withError({ error: ValidateToolError ->
                    SeedBuiltInToolsError.ToolCreationFailed(error)
                }) {
                    toolService.createTool(
                        name = publicName,
                        description = spec.description,
                        type = ToolType.BUILTIN_WORKER,
                        config = JsonObject(emptyMap()),
                        // Persist the real per-tool schema so the LLM can call the tool.
                        inputSchema = spec.inputSchema,
                        outputSchema = null,
                        // Newly registered workers expose their built-in tools enabled by default.
                        isEnabled = true
                    ).bind()
                }

                withError({ error: BuiltInToolDefinitionError ->
                    SeedBuiltInToolsError.LinkageFailed(error)
                }) {
                    toolDefinitionDao.insertTool(
                        toolDefinitionId = created.id,
                        workerId = workerId,
                        builtInToolName = name
                    ).bind()
                }
            }

            logger.info("Seeded built-in tools for worker {} (prefix={})", workerId, toolNamePrefix)
            toolDefinitionDao.getToolsByWorkerId(workerId)
        }
    }

    /**
     * Renames the public names of a worker's built-in tools to reflect a new prefix.
     *
     * Only the public [BuiltInWorkerToolDefinition.name] is changed; the unprefixed
     * [BuiltInWorkerToolDefinition.builtInToolName] is preserved. When [newPrefix] is null or blank,
     * the public names revert to the unprefixed canonical names.
     *
     * @param workerId Owning worker identifier.
     * @param newPrefix New optional prefix (e.g. "project1_"), or null/blank to clear.
     */
    suspend fun renamePublicNamesForPrefix(
        workerId: Long,
        newPrefix: String?
    ): Either<SeedBuiltInToolsError, Unit> = transactionScope.transaction {
        either {
            val prefix = newPrefix?.takeIf { it.isNotBlank() }
            val tools = toolDefinitionDao.getToolsByWorkerId(workerId)
            for (tool in tools) {
                val publicName = buildPublicToolName(prefix, tool.builtInToolName)
                if (publicName != tool.name) {
                    withError({ error: BuiltInToolDefinitionError ->
                        SeedBuiltInToolsError.LinkageFailed(error)
                    }) {
                        toolDefinitionDao.updatePublicName(tool.id, publicName).bind()
                    }
                }
            }
            logger.info("Renamed built-in tool public names for worker {} (prefix={})", workerId, newPrefix)
        }
    }

    /**
     * Reconciles a worker's built-in tool definitions with the current catalog.
     *
     * For every [BuiltInToolCatalog] spec: if the worker has no linkage for that unprefixed name,
     * it is created (idempotent, like seeding). If a linkage already exists, its public name
     * (prefix-aware), description, and input schema are overwritten with the catalog values, while
     * the enabled flag and approval preferences are preserved. This is the server-side counterpart
     * of the "Reset to defaults" action in the UI and is used to bring older workers up to date
     * after new tools are added to the catalog.
     *
     * @param workerId Owning worker identifier.
     * @param toolNamePrefix Optional prefix applied to public tool names (e.g. "project1_").
     * @return Either a [SeedBuiltInToolsError] or the list of [BuiltInWorkerToolDefinition] now owned by the worker.
     */
    suspend fun resetToDefaults(
        workerId: Long,
        toolNamePrefix: String?
    ): Either<SeedBuiltInToolsError, List<BuiltInWorkerToolDefinition>> = transactionScope.transaction {
        either {
            val existing = toolDefinitionDao.getToolsByWorkerId(workerId)
                .associateBy { it.builtInToolName }
            val prefix = toolNamePrefix?.takeIf { it.isNotBlank() }

            for (spec in defaultToolSpecs) {
                val name = spec.builtInToolName
                val publicName = buildPublicToolName(prefix, name)

                val current = existing[name]
                if (current == null) {
                    // Missing tool: create it exactly like seeding does.
                    val created = withError({ error: ValidateToolError ->
                        SeedBuiltInToolsError.ToolCreationFailed(error)
                    }) {
                        toolService.createTool(
                            name = publicName,
                            description = spec.description,
                            type = ToolType.BUILTIN_WORKER,
                            config = JsonObject(emptyMap()),
                            inputSchema = spec.inputSchema,
                            outputSchema = null,
                            // Reset restores defaults, so newly (re)created tools are enabled.
                            isEnabled = true
                        ).bind()
                    }

                    withError({ error: BuiltInToolDefinitionError ->
                        SeedBuiltInToolsError.LinkageFailed(error)
                    }) {
                        toolDefinitionDao.insertTool(
                            toolDefinitionId = created.id,
                            workerId = workerId,
                            builtInToolName = name
                        ).bind()
                    }
                } else if (current.name != publicName ||
                    current.description != spec.description ||
                    current.inputSchema != spec.inputSchema
                ) {
                    // Existing tool: repair only the catalog-derived fields. The enabled flag and
                    // approval preferences are intentionally preserved so an admin's choices survive
                    // a reset. The public name is re-applied only when it actually differs (e.g. after
                    // a prefix change), avoiding needless writes for pre-existing tools.
                    val repaired = current.copy(
                        name = publicName,
                        description = spec.description,
                        inputSchema = spec.inputSchema
                    )
                    withError({ error: UpdateToolError ->
                        when (error) {
                            is UpdateToolError.ToolNotFound ->
                                SeedBuiltInToolsError.LinkageFailed(
                                    BuiltInToolDefinitionError.ReferencedEntityNotFound(
                                        toolDefinitionId = current.id,
                                        workerId = workerId,
                                        message = "Tool definition not found during reset"
                                    )
                                )

                            is UpdateToolError.ValidationError ->
                                SeedBuiltInToolsError.ToolCreationFailed(error.error)
                        }
                    }) {
                        toolService.updateTool(repaired).bind()
                    }
                }
                // else: already in sync, nothing to do.
            }

            logger.info("Reset built-in tools to defaults for worker {} (prefix={})", workerId, toolNamePrefix)
            toolDefinitionDao.getToolsByWorkerId(workerId)
        }
    }
}
