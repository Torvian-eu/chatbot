package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity

/**
 * Data Access Object for managing user accounts.
 * 
 * This DAO provides CRUD operations for user accounts, including user registration,
 * authentication support, and profile management.
 */
interface UserDao {
    /**
     * Retrieves all users in the system.
     * 
     * @return List of all [UserEntity] objects; empty list if no users exist.
     */
    suspend fun getAllUsers(): List<UserEntity>

    /**
     * Retrieves a user by their unique ID.
     * 
     * @param id The unique identifier of the user.
     * @return Either [UserError.UserNotFound] if no user exists with the given ID, 
     *         or the [UserEntity] if found.
     */
    suspend fun getUserById(id: Long): Either<UserError.UserNotFound, UserEntity>

    /**
     * Retrieves a user by their unique username.
     * 
     * @param username The username to search for.
     * @return Either [UserError.UserNotFoundByUsername] if no user exists with the given username,
     *         or the [UserEntity] if found.
     */
    suspend fun getUserByUsername(username: String): Either<UserError.UserNotFoundByUsername, UserEntity>

    /**
     * Creates a new user account.
     * 
     * @param username Unique username for the new user.
     * @param passwordHash Securely hashed password.
     * @param email Optional email address (must be unique if provided).
     * @return Either [UserError.UsernameAlreadyExists] or [UserError.EmailAlreadyExists] if constraints are violated,
     *         or the newly created [UserEntity] on success.
     */
    suspend fun insertUser(
        username: String,
        passwordHash: String,
        email: String? = null
    ): Either<UserError, UserEntity>

    /**
     * Updates an existing user's information.
     * 
     * @param user The [UserEntity] with updated information.
     * @return Either [UserError.UserNotFound] if the user doesn't exist,
     *         [UserError.UsernameAlreadyExists] or [UserError.EmailAlreadyExists] if constraints are violated,
     *         or Unit on success.
     */
    suspend fun updateUser(user: UserEntity): Either<UserError, Unit>

    /**
     * Updates a user's last login timestamp.
     * 
     * @param id The unique identifier of the user.
     * @param lastLogin The timestamp of the last login.
     * @return Either [UserError.UserNotFound] if the user doesn't exist, or Unit on success.
     */
    suspend fun updateLastLogin(id: Long, lastLogin: Long): Either<UserError.UserNotFound, Unit>

    /**
     * Deletes a user account by ID.
     * 
     * @param id The unique identifier of the user to delete.
     * @return Either [UserError.UserNotFound] if the user doesn't exist, or Unit on success.
     */
    suspend fun deleteUser(id: Long): Either<UserError.UserNotFound, Unit>
}
