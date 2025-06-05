package eu.torvian.chatbot.server.service.security

import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import java.util.UUID
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [CredentialManager] using envelope encryption and storing
 * encrypted secrets in a dedicated database table via [ApiSecretDao].
 *
 * This class leverages an [EncryptionService] (which holds the KEK) and an
 * [ApiSecretDao] (which interacts with the database table).
 *
 * The main application tables will store the `alias` (UUID) returned by [storeCredential].
 *
 * Implements [E5.S1, E5.S2, E5.S3] using this database-backed encryption approach.
 * All methods are [suspend] as they interact with the database (via DAO).
 */
class DbEncryptedCredentialManager(
    private val encryptionService: EncryptionService,
    private val apiSecretDao: ApiSecretDao
) : CredentialManager {

    companion object {
        private val logger: Logger = LogManager.getLogger(DbEncryptedCredentialManager::class.java)
    }

    /**
     * Stores a credential securely in the database via envelope encryption.
     *
     * Generates a unique UUID as the alias/reference ID.
     * Uses the [EncryptionService] to encrypt the credential and wrap the DEK.
     * Stores the resulting encrypted data in the dedicated secrets table via [ApiSecretDao].
     *
     * @param credential The sensitive string (e.g., API key) to store.
     * @return A string alias/reference ID (the generated UUID) that can be used to retrieve the credential,
     *         or null if storage failed.
     */
    override suspend fun storeCredential(credential: String): String? {
        val alias = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        try {
            // Use the encryption service to perform envelope encryption
            val encryptedData = encryptionService.encrypt(credential)

            // Save the encrypted data (encrypted credential, wrapped DEK, key version)
            // to the dedicated secrets database table using the DAO.
            apiSecretDao.saveSecret(
                ApiSecretEntity(
                    alias = alias,
                    encryptedCredential = encryptedData.encryptedSecret,
                    wrappedDek = encryptedData.encryptedDEK,
                    keyVersion = encryptedData.keyVersion,
                    createdAt = now,
                    updatedAt = now
                )
            )

            logger.info("CredentialManager: Successfully stored credential in database with alias: $alias")
            return alias // Return the generated alias/ID upon success

        } catch (e: Exception) {
            logger.error("CredentialManager: Failed to store credential in database for alias $alias", e)
            return null // Indicate failure
        }
    }

    /**
     * Retrieves a securely stored credential from the database using its alias.
     *
     * Reads the encrypted data from the dedicated secrets table using the alias via [ApiSecretDao].
     * Uses the [EncryptionService] to unwrap the DEK and decrypt the credential.
     *
     * @param alias The alias/reference ID (the UUID string) of the credential to retrieve.
     * @return The decrypted credential string, or null if not found or retrieval failed.
     */
    override suspend fun getCredential(alias: String): String? {
        try {
            // Find the encrypted data in the secrets table using the DAO
            val encryptedData = apiSecretDao.findSecret(alias)

            if (encryptedData != null) {
                // Use the encryption service to decrypt the credential
                val credentialString = encryptionService.decrypt(
                    EncryptedSecret(
                        encryptedSecret = encryptedData.encryptedCredential,
                        encryptedDEK = encryptedData.wrappedDek,
                        keyVersion = encryptedData.keyVersion
                    )
                )
                logger.debug("CredentialManager: Successfully retrieved credential from database for alias: $alias")
                return credentialString // Return the retrieved credential string
            } else {
                // Secret not found in the database
                logger.debug("CredentialManager: Credential alias not found in database: $alias")
                return null // Indicate not found
            }
        } catch (e: Exception) {
            // This could be a decryption error, DAO error, etc.
            logger.error("CredentialManager: Exception during credential retrieval from database for alias $alias", e)
            return null // Indicate failure
        }
    }

    /**
     * Deletes a securely stored credential from the database using its alias.
     *
     * Deletes the entry from the dedicated secrets table using the alias via [ApiSecretDao].
     *
     * @param alias The alias/reference ID (the UUID string) of the credential to delete.
     * @return True if deletion was successful (or the credential wasn't found), false otherwise on failure.
     */
    override suspend fun deleteCredential(alias: String): Boolean {
         try {
             // Delete the secret from the secrets table using the DAO
             val success = apiSecretDao.deleteSecret(alias)

             if (success) {
                 logger.info("CredentialManager: Successfully deleted credential from database with alias: $alias")
                 return true // Indicate success (DAO returns true on successful DB op, even if 0 rows deleted)
             } else {
                 // apiSecretDao.deleteSecret logs SEVERE errors itself, so just return false here
                 // indicating the DB operation itself failed.
                 return false // Indicate failure
             }
         } catch (e: Exception) {
             // Catch any unexpected exceptions from the DAO call
             logger.error("CredentialManager: Exception during credential delete from database for alias $alias", e)
             return false // Indicate failure due to exception
         }
    }
}
