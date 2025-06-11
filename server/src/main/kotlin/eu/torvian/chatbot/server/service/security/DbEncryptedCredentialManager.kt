package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.service.security.error.CredentialError.CredentialNotFound
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.*

/**
 * Implementation of [CredentialManager] using envelope encryption and storing
 * encrypted secrets in a dedicated database table via [ApiSecretDao].
 *
 * @property encryptionService The service for handling encryption operations
 * @property apiSecretDao The DAO for interacting with the database table
 */
class DbEncryptedCredentialManager(
    private val encryptionService: EncryptionService,
    private val apiSecretDao: ApiSecretDao
) : CredentialManager {

    companion object {
        private val logger: Logger = LogManager.getLogger(DbEncryptedCredentialManager::class.java)
    }

    override suspend fun storeCredential(credential: String): String {
        logger.info("Storing credential in database...")
        val alias = UUID.randomUUID().toString()

        // Use the encryption service to perform envelope encryption
        val encryptedSecret = encryptionService.encrypt(credential)

        // Save encrypted secret to database
        apiSecretDao.saveSecret(alias, encryptedSecret).getOrElse {
            throw IllegalStateException("Failed to save secret to database: alias '$alias' already exists")
        }

        logger.info("Successfully stored credential in database with alias: $alias")
        return alias
    }

    override suspend fun getCredential(alias: String): Either<CredentialNotFound, String> =
        either {
            withError({ CredentialNotFound(alias) }) {
                apiSecretDao.getSecret(alias).bind().let { encryptedSecret ->
                    encryptionService.decrypt(encryptedSecret)
                }
            }
        }


    override suspend fun deleteCredential(alias: String): Either<CredentialNotFound, Unit> =
        either {
            withError({ CredentialNotFound(alias) }) {
                apiSecretDao.deleteSecret(alias).bind()
            }
        }
}
