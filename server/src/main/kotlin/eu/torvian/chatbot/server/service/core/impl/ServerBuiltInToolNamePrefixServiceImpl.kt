package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.tool.ToolNamePrefixValidator
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixService
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolNamePrefixError
import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default implementation of [ServerBuiltInToolNamePrefixService].
 *
 * Mirrors the worker prefix update path ([eu.torvian.chatbot.server.service.core.impl.WorkerServiceImpl]
 * `updateWorker` → `BuiltInToolDefinitionSeeder.renamePublicNamesForPrefix`): validation happens
 * before any persistence, then the preference row and the tool renames commit in the same
 * transaction. Because [TransactionScope] rolls back when the block returns `Either.Left`, a
 * rename failure also rolls back the preference write.
 *
 * @property userPreferenceDao DAO used to upsert/delete the global preference row.
 * @property serverBuiltInToolDefinitionSeeder Seeder that performs the per-user public-name rename.
 * @property prefixResolver Resolver used to determine the reset target prefix on delete.
 * @property transactionScope Transaction boundary making the preference write + rename atomic.
 */
class ServerBuiltInToolNamePrefixServiceImpl(
    private val userPreferenceDao: UserPreferenceDao,
    private val serverBuiltInToolDefinitionSeeder: ServerBuiltInToolDefinitionSeeder,
    private val prefixResolver: ServerBuiltInToolNamePrefixResolver,
    private val transactionScope: TransactionScope,
) : ServerBuiltInToolNamePrefixService {

    private val logger: Logger = LogManager.getLogger(ServerBuiltInToolNamePrefixServiceImpl::class.java)

    private val toolNamePrefixValidator = ToolNamePrefixValidator()

    override suspend fun updatePrefix(
        userId: Long,
        prefix: String
    ): Either<UpdateServerBuiltInToolNamePrefixError, Unit> = transactionScope.transaction {
        either {
            // Normalize before validating so a whitespace-only value is treated as "no prefix"
            // rather than rejected (mirrors the worker semantics where blank ⇒ null).
            val normalized = prefix.ifBlank { "" }

            // Reject prefixes with illegal characters before any persistence or rename happens.
            toolNamePrefixValidator.validate(normalized)?.let { reason ->
                raise(UpdateServerBuiltInToolNamePrefixError.InvalidInput("Invalid tool name prefix: $reason"))
            }

            userPreferenceDao.upsertPreference(
                userId = userId,
                internalDeviceId = null,
                clientDeviceId = null,
                key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
                value = normalized
            )

            // Rename the persisted public names in the same transaction; a failure rolls back the
            // preference write above (the transaction block returns Either.Left).
            withError({ error: SeedServerBuiltInToolsError ->
                UpdateServerBuiltInToolNamePrefixError.RenameFailed(error)
            }) {
                serverBuiltInToolDefinitionSeeder.renamePublicNamesForPrefix(userId, normalized).bind()
            }

            logger.info("Updated server built-in tool name prefix for user {} to '{}'", userId, normalized)
        }
    }

    override suspend fun deletePrefix(
        userId: Long
    ): Either<UpdateServerBuiltInToolNamePrefixError, Unit> = transactionScope.transaction {
        either {
            userPreferenceDao.deletePreference(
                userId = userId,
                internalDeviceId = null,
                key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX
            )

            // After deleting the row, resolvePrefix returns the server default; using the resolver
            // here keeps the future configurable-default seam in one place.
            val resetPrefix = prefixResolver.resolvePrefix(userId)

            withError({ error: SeedServerBuiltInToolsError ->
                UpdateServerBuiltInToolNamePrefixError.RenameFailed(error)
            }) {
                serverBuiltInToolDefinitionSeeder.renamePublicNamesForPrefix(userId, resetPrefix).bind()
            }

            logger.info("Deleted server built-in tool name prefix for user {} (reset to '{}')", userId, resetPrefix)
        }
    }
}
