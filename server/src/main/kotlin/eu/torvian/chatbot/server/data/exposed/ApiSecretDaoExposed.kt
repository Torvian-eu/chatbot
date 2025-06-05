package eu.torvian.chatbot.server.data.exposed

import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.data.models.ApiSecretsTable
import eu.torvian.chatbot.server.data.models.toApiSecretEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Exposed ORM implementation of [ApiSecretDao].
 *
 * Manages encrypted API secrets in the `api_secrets` database table using Exposed.
 *
 * @property transactionScope The transaction scope for database access within coroutines.
 */
class ApiSecretDaoExposed(
    private val transactionScope: TransactionScope
) : ApiSecretDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(ApiSecretDaoExposed::class.java)
    }

    override suspend fun saveSecret(apiSecretEntity: ApiSecretEntity) =
        transactionScope.transaction {
            // Check if exists
            val existing = ApiSecretsTable.selectAll().where {
                ApiSecretsTable.alias eq apiSecretEntity.alias
            }.singleOrNull()

            if (existing == null) {
                // Insert new secret
                ApiSecretsTable.insert {
                    it[alias] = apiSecretEntity.alias
                    it[encrypted_credential] = apiSecretEntity.encryptedCredential
                    it[wrapped_dek] = apiSecretEntity.wrappedDek
                    it[key_version] = apiSecretEntity.keyVersion
                    it[created_at] = apiSecretEntity.createdAt
                    it[updated_at] = apiSecretEntity.updatedAt
                }
                logger.debug("DAO: Inserted new secret with alias: ${apiSecretEntity.alias}")
            } else {
                // Update existing secret
                ApiSecretsTable.update({
                    ApiSecretsTable.alias eq apiSecretEntity.alias
                }) {
                    it[encrypted_credential] = apiSecretEntity.encryptedCredential
                    it[wrapped_dek] = apiSecretEntity.wrappedDek
                    it[key_version] = apiSecretEntity.keyVersion
                    it[updated_at] = apiSecretEntity.updatedAt
                }
                logger.debug("DAO: Updated existing secret with alias: ${apiSecretEntity.alias}")
            }
        }

    override suspend fun findSecret(alias: String): ApiSecretEntity? =
        transactionScope.transaction {
            ApiSecretsTable
                .selectAll().where { ApiSecretsTable.alias eq alias }
                .singleOrNull()?.toApiSecretEntity()
                .also {
                    if (it == null) {
                        logger.debug("DAO: Secret with alias $alias not found in database.")
                    } else {
                        logger.debug("DAO: Found secret with alias $alias in database.")
                    }
                }
        }

    override suspend fun deleteSecret(alias: String): Boolean =
        transactionScope.transaction {
            try {
                val deletedRows = ApiSecretsTable.deleteWhere { ApiSecretsTable.alias eq alias }
                logger.debug("DAO: Attempted to delete secret with alias $alias. Rows deleted: $deletedRows")
                // Return true if the delete operation itself succeeded (didn't throw), regardless of row count.
                // The service/caller can interpret 0 rows deleted as "not found", which is success for delete semantics.
                true
            } catch (e: Exception) {
                logger.error("DAO: Exception during database delete for alias $alias", e)
                false // Indicate failure if a database exception occurs
            }
        }
}
