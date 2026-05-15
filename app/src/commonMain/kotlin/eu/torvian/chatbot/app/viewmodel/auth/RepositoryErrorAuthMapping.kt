package eu.torvian.chatbot.app.viewmodel.auth

import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.getStringDetail
import eu.torvian.chatbot.app.repository.matches
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import kotlin.math.ceil

/**
 * Maps this [RepositoryError] to a user-friendly login error message.
 * Uses structured checks against [CommonApiErrorCodes] for reliable error identification
 * instead of fragile string matching.
 *
 * @return A user-friendly error message suitable for display in the login form.
 */
fun RepositoryError.mapLoginError(): String = when {
    matches(CommonApiErrorCodes.INVALID_CREDENTIALS) ->
        "Invalid username or password"

    matches(CommonApiErrorCodes.VERIFICATION_REQUIRED) ->
        "Login blocked from an untrusted device. Approve this device from an existing trusted session or contact an administrator."

    matches(CommonApiErrorCodes.PERMISSION_DENIED) ->
        "Account is temporarily locked. Please try again later."

    matches(CommonApiErrorCodes.TOO_MANY_ATTEMPTS) ->
        "Too many failed login attempts. Please wait a few minutes and try again."

    else -> "An unexpected error occurred. Please try again."
}

/**
 * Maps this [RepositoryError] to a user-friendly registration error message.
 * Uses structured checks against [CommonApiErrorCodes] for reliable error identification
 * instead of fragile string matching.
 *
 * For [CommonApiErrorCodes.ALREADY_EXISTS], inspects the error details to distinguish
 * between username and email conflicts.
 *
 * @return A user-friendly error message suitable for display in the registration form.
 */
fun RepositoryError.mapRegistrationError(): String = when {
    matches(CommonApiErrorCodes.ALREADY_EXISTS) -> {
        val isEmailConflict = getStringDetail("field") == "email"

        if (isEmailConflict) {
            "Email is already registered. Please use a different email or try logging in."
        } else {
            "Username is already taken. Please choose a different one."
        }
    }

    else -> "An unexpected error occurred. Please try again."
}

/**
 * Maps this [RepositoryError] to a user-friendly password change error message.
 * Uses structured checks against [CommonApiErrorCodes] for reliable error identification
 * instead of fragile string matching.
 *
 * @return A user-friendly error message suitable for display in the password change form.
 */
fun RepositoryError.mapPasswordChangeError(): String = when {
    matches(CommonApiErrorCodes.INVALID_ARGUMENT) ->
        "New password is too weak. Please choose a stronger password."

    matches(CommonApiErrorCodes.PERMISSION_DENIED) ->
        "Action requires a trusted session. Please verify your identity."

    matches(CommonApiErrorCodes.INVALID_CREDENTIALS) ->
        "Current password is incorrect."

    else -> "Password change failed. Please try again."
}

/**
 * Maps this [RepositoryError] to a user-friendly email change error message.
 * Uses structured checks against [CommonApiErrorCodes] for reliable error identification
 * instead of fragile string matching.
 *
 * @return A user-friendly error message suitable for display in the email change form.
 */
fun RepositoryError.mapEmailChangeError(): String = when {
    matches(CommonApiErrorCodes.INVALID_ARGUMENT) ->
        "New email is invalid. Please enter a valid email address."

    matches(CommonApiErrorCodes.PERMISSION_DENIED) ->
        "Action requires a trusted session. Please verify your identity."

    matches(CommonApiErrorCodes.INVALID_CREDENTIALS) ->
        "Current password is incorrect."

    else -> "Email change failed. Please try again."
}

/**
 * Maps this [RepositoryError] to a user-friendly verification error message.
 * Handles RATE_LIMIT, FAILED_PRECONDITION, and other errors for verification operations.
 *
 * @return A user-friendly error message suitable for display in verification flows.
 */
fun RepositoryError.mapVerificationError(): String {
    return when {
        matches(CommonApiErrorCodes.RATE_LIMIT) -> {
            val retryAfterSeconds = getStringDetail("retryAfterSeconds")?.toLongOrNull()

            if (retryAfterSeconds != null) {
                val minutes = ceil(retryAfterSeconds / 60.0).toLong()
                "Email already sent. Please wait $minutes minute${if (minutes != 1L) "s" else ""} before trying again."
            } else {
                "Email already sent. Please wait before trying again."
            }
        }
        matches(CommonApiErrorCodes.FAILED_PRECONDITION) ->
            "User has no email address on file. Please add an email to your account first."
        else -> "Failed to request verification email. Please try again."
    }
}
