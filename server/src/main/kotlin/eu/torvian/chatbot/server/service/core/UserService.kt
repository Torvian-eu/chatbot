package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError

/**
 * Service interface for user management operations.
 * 
 * This service provides high-level operations for user account management including
 * registration, lookup, and profile updates. It handles business logic such as
 * password validation, automatic group assignment, and user lifecycle management.
 */
interface UserService {
    /**
     * Registers a new user account with automatic group assignment.
     * 
     * This method:
     * 1. Validates password strength
     * 2. Hashes the password securely
     * 3. Creates the user account
     * 4. Automatically adds the user to the "All Users" group
     * 
     * @param username Unique username for the new user
     * @param password Plaintext password (will be hashed)
     * @param email Optional email address (must be unique if provided)
     * @return Either [RegisterUserError] if registration fails, or the newly created [User]
     */
    suspend fun registerUser(
        username: String, 
        password: String, 
        email: String? = null
    ): Either<RegisterUserError, User>

    /**
     * Retrieves a user by their username.
     * 
     * @param username The username to search for
     * @return Either [UserNotFoundError.ByUsername] if not found, or the [User]
     */
    suspend fun getUserByUsername(username: String): Either<UserNotFoundError.ByUsername, User>

    /**
     * Retrieves a user by their unique ID.
     * 
     * @param id The user ID to search for
     * @return Either [UserNotFoundError.ById] if not found, or the [User]
     */
    suspend fun getUserById(id: Long): Either<UserNotFoundError.ById, User>

    /**
     * Updates a user's last login timestamp.
     * 
     * @param userId The unique identifier of the user
     * @return Either [UserNotFoundError.ById] if user not found, or Unit on success
     */
    suspend fun updateLastLogin(userId: Long): Either<UserNotFoundError.ById, Unit>
    
    /**
     * Retrieves all users in the system.
     * 
     * Note: This method is typically restricted to administrators.
     * 
     * @return List of all [User] objects; empty list if no users exist
     */
    suspend fun getAllUsers(): List<User>
}
