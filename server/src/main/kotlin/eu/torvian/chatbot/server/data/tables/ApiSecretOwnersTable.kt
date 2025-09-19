package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Links an API secret to its owning user.
 * 
 * This table establishes a one-to-one relationship between API secrets
 * and their owners. Each secret has exactly one owner, and ownership
 * determines who can access, modify, or delete the secret.
 * 
 * @property secretAlias Reference to the API secret alias being owned
 * @property userId Reference to the user who owns the secret
 */
object ApiSecretOwnersTable : Table("api_secret_owners") {
    val secretAlias = reference("secret_alias", ApiSecretTable.alias, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    // secret_alias is primary key, ensuring 1 owner per secret
    override val primaryKey = PrimaryKey(secretAlias)
}
