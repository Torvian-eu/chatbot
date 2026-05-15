package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.server.service.core.error.auth.ChangeEmailError
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
import eu.torvian.chatbot.server.service.security.error.CompleteRequiredPasswordChangeError

/**
 * Service for user account management operations.
 *
 * Manages account-related changes including password and email modifications.
 */
interface AccountManagementService {
    /**
     * Changes the password for an authenticated user.
     *
     * This method:
     * 1. Checks if the requester is restricted (untrusted session) - blocked if so
     * 2. Verifies the current password matches the stored hash
     * 3. Validates the new password meets strength requirements
     * 4. Updates the password and clears the requiresPasswordChange flag
     *
     * @param userId The unique identifier of the user changing their password
     * @param currentPassword The user's current password for verification
     * @param newPassword The new password to set
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [ChangePasswordError] if the operation fails, or Unit on success
     */
    suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<ChangePasswordError, Unit>

    /**
     * Changes the email address for an authenticated user.
     *
     * This method:
     * 1. Checks if the requester is restricted (untrusted session) - blocked if so
     * 2. Verifies the current password matches the stored hash
     * 3. Validates the new email format and uniqueness
     * 4. Updates the email and returns the updated user
     *
     * @param userId The unique identifier of the user changing their email
     * @param currentPassword The user's current password for verification
     * @param newEmail The new email address to set
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [ChangeEmailError] if the operation fails, or the updated [User] on success
     */
    suspend fun changeEmail(
        userId: Long,
        currentPassword: String,
        newEmail: String,
        requesterIsRestricted: Boolean
    ): Either<ChangeEmailError, User>

    /**
     * Completes a server-required password change for an authenticated user.
     *
     * This method is used when a user is forced to change their password
     * (requiresPasswordChange = true). Unlike normal password change, it does not
     * require the current password, but:
     * 1. Checks if the requester is restricted (untrusted session) - blocked if so
     * 2. Verifies the user's requiresPasswordChange flag is true
     * 3. Validates the new password meets strength requirements
     * 4. Updates the password and clears the requiresPasswordChange flag
     *
     * @param userId The unique identifier of the user changing their password
     * @param newPassword The new password to set
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [CompleteRequiredPasswordChangeError] if the operation fails, or Unit on success
     */
    suspend fun completeRequiredPasswordChange(
        userId: Long,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<CompleteRequiredPasswordChangeError, Unit>
}

