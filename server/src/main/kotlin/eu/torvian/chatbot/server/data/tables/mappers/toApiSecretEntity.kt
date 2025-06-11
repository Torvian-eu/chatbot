package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.ApiSecretEntity
import eu.torvian.chatbot.server.data.tables.ApiSecretTable
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [org.jetbrains.exposed.sql.ResultRow] to an [eu.torvian.chatbot.server.data.entities.ApiSecretEntity].
 */
fun ResultRow.toApiSecretEntity(): ApiSecretEntity {
    return ApiSecretEntity(
        alias = this[ApiSecretTable.alias],
        encryptedCredential = this[ApiSecretTable.encrypted_credential],
        wrappedDek = this[ApiSecretTable.wrapped_dek],
        keyVersion = this[ApiSecretTable.key_version],
        createdAt = this[ApiSecretTable.created_at],
        updatedAt = this[ApiSecretTable.updated_at]
    )
}