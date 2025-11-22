package eu.torvian.chatbot.app.service.misc

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.database.dao.EncryptedSecretLocalDao
import eu.torvian.chatbot.app.database.dao.error.DeleteEncryptedSecretError
import eu.torvian.chatbot.app.database.dao.error.UpdateEncryptedSecretError
import eu.torvian.chatbot.app.database.model.EncryptedSecretEntity
import eu.torvian.chatbot.common.security.EncryptionService
import kotlinx.datetime.Clock

/**
 * Implementation of EncryptedSecretService using an EncryptionService and
 * a local database DAO.
 *
 * @property encryptionService The service for encryption and decryption
 * @property dao The DAO for database access
 * @property clock The clock for timestamping
 */
class EncryptedSecretServiceImpl(
    private val encryptionService: EncryptionService,
    private val dao: EncryptedSecretLocalDao,
    private val clock: Clock
) : EncryptedSecretService {
    override suspend fun encryptAndStore(plainText: String): Either<EncryptAndStoreError, EncryptedSecretEntity> =
        either {
            // Encrypt the plaintext
            val encryptedSecret = encryptionService.encrypt(plainText)
                .mapLeft { cryptoError ->
                    EncryptAndStoreError.EncryptionFailed(
                        plainTextLength = plainText.length,
                        reason = "Failed to encrypt secret: $cryptoError"
                    )
                }
                .bind()

            // Get current timestamp
            val now = clock.now().toEpochMilliseconds()

            // Store in database and return its id
            val inserted = dao.insert(
                encryptedSecret = encryptedSecret.encryptedSecret,
                encryptedDEK = encryptedSecret.encryptedDEK,
                keyVersion = encryptedSecret.keyVersion,
                createdAt = now,
                updatedAt = now
            )

            inserted
        }

    override suspend fun retrieveAndDecrypt(secretId: Long): Either<RetrieveAndDecryptError, String> = either {
        // Retrieve from database (map DAO NotFound to service SecretNotFound)
        val entity = dao.getById(secretId)
            .mapLeft { _ -> RetrieveAndDecryptError.SecretNotFound(secretId) }
            .bind()

        // Decrypt
        val encryptedSecret = entity.toEncryptedSecret()
        encryptionService.decrypt(encryptedSecret)
            .mapLeft { cryptoError ->
                RetrieveAndDecryptError.DecryptionFailed(
                    secretId = secretId,
                    keyVersion = entity.keyVersion,
                    reason = "Failed to decrypt secret: $cryptoError"
                )
            }
            .bind()
    }

    override suspend fun updateSecret(
        secretId: Long,
        newPlainText: String
    ): Either<UpdateSecretError, Unit> = either {
        // Verify secret exists
        dao.getById(secretId)
            .mapLeft { _ -> UpdateSecretError.SecretNotFound(secretId) }
            .bind()

        // Encrypt new data
        val encryptedSecret = encryptionService.encrypt(newPlainText)
            .mapLeft { cryptoError ->
                UpdateSecretError.EncryptionFailed(
                    secretId = secretId,
                    newPlainTextLength = newPlainText.length,
                    reason = "Failed to encrypt secret: $cryptoError"
                )
            }
            .bind()

        // Update in database
        val now = clock.now().toEpochMilliseconds()
        dao.update(
            id = secretId,
            encryptedSecret = encryptedSecret.encryptedSecret,
            encryptedDEK = encryptedSecret.encryptedDEK,
            keyVersion = encryptedSecret.keyVersion,
            updatedAt = now
        ).mapLeft { updateError ->
            when (updateError) {
                is UpdateEncryptedSecretError.NotFound -> UpdateSecretError.SecretNotFound(secretId)
            }
        }.bind()
    }

    override suspend fun deleteSecret(secretId: Long): Either<DeleteSecretError, Unit> = either {
        dao.deleteById(secretId).mapLeft { deleteError ->
            when (deleteError) {
                is DeleteEncryptedSecretError.NotFound -> DeleteSecretError.SecretNotFound(secretId)
                is DeleteEncryptedSecretError.ForeignKeyViolation -> DeleteSecretError.SecretInUse(
                    secretId = secretId,
                    errorMessage = deleteError.message
                )
            }
        }.bind()
    }
}