package eu.torvian.chatbot.server.data.entities.mappers

import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.server.data.entities.UserEntity

/**
 * Extension function to map a [UserEntity] to a [User] for API responses.
 *
 * This mapper excludes sensitive information like password hashes and updatedAt
 * timestamps that are not needed for client-side user representation.
 */
fun UserEntity.toUser(): User {
    return User(
        id = this.id,
        username = this.username,
        email = this.email,
        status = this.status,
        createdAt = this.createdAt,
        lastLogin = this.lastLogin,
        requiresPasswordChange = this.requiresPasswordChange
    )
}
