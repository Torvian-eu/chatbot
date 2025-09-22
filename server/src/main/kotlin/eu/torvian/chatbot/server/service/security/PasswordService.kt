package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.error.PasswordValidationError

/**
 * Service interface for password-related operations including hashing, verification, and validation.
 * 
 * This service provides secure password handling capabilities using industry-standard practices:
 * - Password hashing using BCrypt with configurable salt rounds
 * - Password verification against stored hashes
 * - Password strength validation with configurable rules
 */
interface PasswordService {
    /**
     * Hashes a plaintext password using a secure hashing algorithm.
     * 
     * @param plainPassword The plaintext password to hash
     * @return The securely hashed password string
     */
    fun hashPassword(plainPassword: String): String
    
    /**
     * Verifies a plaintext password against a stored hash.
     * 
     * @param plainPassword The plaintext password to verify
     * @param hashedPassword The stored password hash to verify against
     * @return true if the password matches the hash, false otherwise
     */
    fun verifyPassword(plainPassword: String, hashedPassword: String): Boolean
    
    /**
     * Validates password strength according to configured security policies.
     * 
     * @param password The password to validate
     * @return Either [PasswordValidationError] if validation fails, or Unit if valid
     */
    fun validatePasswordStrength(password: String): Either<PasswordValidationError, Unit>
}
