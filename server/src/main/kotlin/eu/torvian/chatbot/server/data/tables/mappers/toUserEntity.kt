package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.tables.UsersTable
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [UserEntity].
 */
fun ResultRow.toUserEntity(): UserEntity {
    return UserEntity(
        id = this[UsersTable.id].value,
        username = this[UsersTable.username],
        passwordHash = this[UsersTable.passwordHash],
        email = this[UsersTable.email],
        createdAt = Instant.fromEpochMilliseconds(this[UsersTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[UsersTable.updatedAt]),
        lastLogin = this[UsersTable.lastLogin]?.let { Instant.fromEpochMilliseconds(it) }
    )
}
