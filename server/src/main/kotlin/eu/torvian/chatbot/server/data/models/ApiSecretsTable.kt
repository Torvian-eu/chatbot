package eu.torvian.chatbot.server.data.models

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

/**
 * Represents the database table schema for storing encrypted API secrets and other sensitive data.
 *
 * This table holds the encrypted credential, the wrapped Data Encryption Key (DEK),
 * and the Key Encryption Key (KEK) version used for envelope encryption.
 *
 * @property alias The unique identifier (UUID) for the secret, used as the primary key and reference from other tables (like LLMModels).
 * @property encrypted_credential The API key (or other sensitive data) encrypted with a DEK, Base64 encoded.
 * @property wrapped_dek The Data Encryption Key (DEK) encrypted with the Key Encryption Key (KEK), Base64 encoded.
 * @property key_version The version of the Key Encryption Key (KEK) used for encryption.
 * @property created_at Timestamp when the secret was created.
 * @property updated_at Timestamp when the secret was last updated.
 */
object ApiSecretsTable : Table("api_secrets") {
    // The alias (UUID) is the primary key, used to reference this secret from other tables.
    // VARCHAR(36) is standard for UUID string representation.
    val alias: Column<String> = varchar("alias", 36)
    // Using text for encrypted_credential allows for potentially larger encrypted data
    val encrypted_credential: Column<String> = text("encrypted_credential")
    // varchar(255) should be sufficient for a Base64 encoded 256-bit AES wrapped DEK + IV (~65 chars)
    val wrapped_dek: Column<String> = varchar("wrapped_dek", 255)
    val key_version: Column<Int> = integer("key_version") // Link to EncryptionConfig.keyVersion
    val created_at = long("created_at")
    val updated_at = long("updated_at") // Could be updated on write/overwrite

    override val primaryKey = PrimaryKey(alias)
}