package eu.torvian.chatbot.server.data.models

import org.jetbrains.exposed.sql.ResultRow

/**
 * Represents a row from the 'api_secrets' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property alias Unique identifier for the secret (UUID string).
 * @property encryptedCredential The encrypted sensitive data (Base64).
 * @property wrappedDek The wrapped Data Encryption Key (DEK) (Base64).
 * @property keyVersion The version of the Key Encryption Key (KEK) used.
 * @property createdAt Timestamp when the secret was created (epoch milliseconds).
 * @property updatedAt Timestamp when the secret was last updated (epoch milliseconds).
 */
data class ApiSecretEntity(
    val alias: String,
    val encryptedCredential: String,
    val wrappedDek: String,
    val keyVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Extension function to map an Exposed [ResultRow] to an [ApiSecretEntity].
 */
fun ResultRow.toApiSecretEntity(): ApiSecretEntity {
    return ApiSecretEntity(
        alias = this[ApiSecretsTable.alias],
        encryptedCredential = this[ApiSecretsTable.encrypted_credential],
        wrappedDek = this[ApiSecretsTable.wrapped_dek],
        keyVersion = this[ApiSecretsTable.key_version],
        createdAt = this[ApiSecretsTable.created_at],
        updatedAt = this[ApiSecretsTable.updated_at]
    )
}