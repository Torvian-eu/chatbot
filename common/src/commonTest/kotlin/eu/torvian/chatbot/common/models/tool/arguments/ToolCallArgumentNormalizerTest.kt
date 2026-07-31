package eu.torvian.chatbot.common.models.tool.arguments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Tests the narrow, replay-boundary argument normalization contract. */
class ToolCallArgumentNormalizerTest {
    /** Literal control characters are escaped without changing their decoded value. */
    @Test
    fun `literal tab is normalized`() {
        val result = ToolCallArgumentNormalizer.normalize("{\"text\":\"a\tb\"}")
        assertEquals("{\"text\":\"a\\tb\"}", assertIs<ToolCallArgumentNormalizer.Result.Valid>(result).value)
    }

    /** Existing JSON escapes are not double-escaped. */
    @Test
    fun `escaped tab remains unchanged`() {
        val input = "{\"text\":\"a\\tb\"}"
        assertEquals(input, assertIs<ToolCallArgumentNormalizer.Result.Valid>(ToolCallArgumentNormalizer.normalize(input)).value)
    }

    /** Structural syntax errors remain failures rather than being guessed at. */
    @Test
    fun `missing brace is not repaired`() {
        assertIs<ToolCallArgumentNormalizer.Result.Invalid>(ToolCallArgumentNormalizer.normalize("{\"text\":\"x\""))
    }
}
