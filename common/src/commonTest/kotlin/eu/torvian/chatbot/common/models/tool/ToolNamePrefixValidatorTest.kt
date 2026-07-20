package eu.torvian.chatbot.common.models.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [ToolNamePrefixValidator], covering the allowed character set, length bounds, and the
 * special "blank means no prefix" rule.
 */
class ToolNamePrefixValidatorTest {

    private val validator = ToolNamePrefixValidator()

    @Test
    fun `blank prefix is valid`() {
        assertNull(validator.validate(""))
        assertNull(validator.validate("   "))
    }

    @Test
    fun `legal prefixes are valid`() {
        assertNull(validator.validate("project1_"))
        assertNull(validator.validate("proj-"))
        assertNull(validator.validate("a"))
        assertNull(validator.validate("ABC123_-"))
    }

    @Test
    fun `prefix with dot is rejected`() {
        assertEquals(
            "Prefix can only contain letters, numbers, hyphens, and underscores",
            validator.validate("bad.prefix")
        )
    }

    @Test
    fun `prefix with space is rejected`() {
        assertEquals(
            "Prefix can only contain letters, numbers, hyphens, and underscores",
            validator.validate("bad prefix")
        )
    }

    @Test
    fun `prefix with slash is rejected`() {
        assertEquals(
            "Prefix can only contain letters, numbers, hyphens, and underscores",
            validator.validate("bad/prefix")
        )
    }

    @Test
    fun `prefix exceeding max length is rejected`() {
        val tooLong = "a".repeat(ToolNamePrefixValidationConfig.DEFAULT_MAX_LENGTH + 1)
        assertEquals(
            "Prefix must be no more than ${ToolNamePrefixValidationConfig.DEFAULT_MAX_LENGTH} characters",
            validator.validate(tooLong)
        )
    }
}

