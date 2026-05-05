package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.common.security.error.CharacterType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BCryptPasswordServiceTest {

    private val passwordService = BCryptPasswordService()

    @Test
    fun `hashPassword should return different hashes for same password`() {
        val password = "testPassword123!"
        val hash1 = passwordService.hashPassword(password)
        val hash2 = passwordService.hashPassword(password)

        // Hashes should be different due to salt
        assertTrue(hash1 != hash2)

        // But both should verify correctly
        assertTrue(passwordService.verifyPassword(password, hash1))
        assertTrue(passwordService.verifyPassword(password, hash2))
    }

    @Test
    fun `verifyPassword should return true for correct password`() {
        val password = "correctPassword123!"
        val hash = passwordService.hashPassword(password)

        assertTrue(passwordService.verifyPassword(password, hash))
    }

    @Test
    fun `verifyPassword should return false for incorrect password`() {
        val correctPassword = "correctPassword123!"
        val incorrectPassword = "wrongPassword123!"
        val hash = passwordService.hashPassword(correctPassword)

        assertFalse(passwordService.verifyPassword(incorrectPassword, hash))
    }

    @Test
    fun `verifyPassword should handle invalid hash gracefully`() {
        val password = "testPassword123!"
        val invalidHash = "not-a-valid-bcrypt-hash"

        assertFalse(passwordService.verifyPassword(password, invalidHash))
    }

    @Test
    fun `validatePasswordStrength should accept strong password`() {
        val strongPassword = "StrongPass123!"
        val result = passwordService.validatePasswordStrength(strongPassword)

        assertEquals(Unit.right(), result)
    }

    @Test
    fun `validatePasswordStrength should reject empty password`() {
        val result = passwordService.validatePasswordStrength("")

        assertEquals(PasswordValidationError.Empty.left(), result)
    }

    @Test
    fun `validatePasswordStrength should reject whitespace-only password`() {
        val result = passwordService.validatePasswordStrength("   \t\n  ")

        assertEquals(PasswordValidationError.OnlyWhitespace.left(), result)
    }

    @Test
    fun `validatePasswordStrength should reject too short password`() {
        val shortPassword = "Short1!"
        val result = passwordService.validatePasswordStrength(shortPassword)

        assertEquals(PasswordValidationError.TooShort(8, shortPassword.length).left(), result)
    }

    @Test
    fun `validatePasswordStrength should reject too long password`() {
        val longPassword = "A".repeat(129) + "1!"
        val result = passwordService.validatePasswordStrength(longPassword)

        assertEquals(PasswordValidationError.TooLong(128, longPassword.length).left(), result)
    }

    @Test
    fun `validatePasswordStrength should reject password missing uppercase`() {
        val result = passwordService.validatePasswordStrength("lowercase123!")

        val expected = PasswordValidationError.MissingCharacterTypes(listOf(CharacterType.UPPERCASE)).left()
        assertEquals(expected, result)
    }

    @Test
    fun `validatePasswordStrength should reject password missing lowercase`() {
        val result = passwordService.validatePasswordStrength("UPPERCASE123!")

        val expected = PasswordValidationError.MissingCharacterTypes(listOf(CharacterType.LOWERCASE)).left()
        assertEquals(expected, result)
    }

    @Test
    fun `validatePasswordStrength should reject password missing digits`() {
        val result = passwordService.validatePasswordStrength("NoDigitsHere!")

        val expected = PasswordValidationError.MissingCharacterTypes(listOf(CharacterType.DIGITS)).left()
        assertEquals(expected, result)
    }

    @Test
    fun `validatePasswordStrength should reject password missing special characters`() {
        val result = passwordService.validatePasswordStrength("NoSpecialChars123")

        val expected = PasswordValidationError.MissingCharacterTypes(listOf(CharacterType.SPECIAL_CHARACTERS)).left()
        assertEquals(expected, result)
    }

    @Test
    fun `validatePasswordStrength should reject password missing multiple character types`() {
        val result = passwordService.validatePasswordStrength("onlylowercase")

        val expected = PasswordValidationError.MissingCharacterTypes(
            listOf(CharacterType.UPPERCASE, CharacterType.DIGITS, CharacterType.SPECIAL_CHARACTERS)
        ).left()
        assertEquals(expected, result)
    }

    @Test
    fun `validatePasswordStrength should reject common weak passwords`() {
        val commonPasswords = listOf(
            "Password123!",
            "Qwerty123!",
            "Admin123!",
            "Welcome123!"
        )

        commonPasswords.forEach { password ->
            val result = passwordService.validatePasswordStrength(password)
            assertTrue(result.isLeft(), "Password '$password' should be rejected as too common")

            val error = result.leftOrNull()
            assertIs<PasswordValidationError.TooCommon>(error, "Expected TooCommon error for '$password', got $error")
        }
    }

    @Test
    fun `validatePasswordStrength should accept password at minimum length`() {
        val result = passwordService.validatePasswordStrength("MinLen1!")

        assertEquals(Unit.right(), result)
    }

    @Test
    fun `validatePasswordStrength should accept password at maximum length`() {
        val maxLengthPassword =
            "AzByCxDwEvFuGtHsIrJqKpLoMnNmOlPkQjRiShTgUfVeWdXcYbZa".repeat(2) + "AzByCxDwEvFuGtHsIr1!"
        val result = passwordService.validatePasswordStrength(maxLengthPassword)

        assertEquals(Unit.right(), result)
    }
}
