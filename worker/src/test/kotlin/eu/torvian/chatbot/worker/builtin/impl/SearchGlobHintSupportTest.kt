package eu.torvian.chatbot.worker.builtin.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Unit tests for glob hint support helpers such as [isUnintentionalLeadingSlashStarStar] and [fixLeadingSlashStarStar].
 */
class SearchGlobHintSupportTest {

    @Test
    fun `isUnintentionalLeadingSlashStarStar identifies unintentional patterns correctly`() {
        // Triggers hint (unintentional leading **/ with simple top-level file/extension glob)
        assertTrue(isUnintentionalLeadingSlashStarStar("**/*.kt"))
        assertTrue(isUnintentionalLeadingSlashStarStar("**/README.md"))

        // Does NOT trigger hint (intentional nested or path-anchored or already fixed)
        assertFalse(isUnintentionalLeadingSlashStarStar("**/**.kt"))
        assertFalse(isUnintentionalLeadingSlashStarStar("**/src/*.kt"))
        assertFalse(isUnintentionalLeadingSlashStarStar("**/*/*.kt"))
        assertFalse(isUnintentionalLeadingSlashStarStar("**.kt"))
        assertFalse(isUnintentionalLeadingSlashStarStar("README.md"))
    }

    @Test
    fun `fixLeadingSlashStarStar transforms patterns correctly without triple stars`() {
        assertEquals("**.kt", fixLeadingSlashStarStar("**/*.kt"))
        assertEquals("**README.md", fixLeadingSlashStarStar("**/README.md"))

        // Unchanged when not unintentional
        assertEquals("**/**.kt", fixLeadingSlashStarStar("**/**.kt"))
        assertEquals("**/src/*.kt", fixLeadingSlashStarStar("**/src/*.kt"))
        assertEquals("**/*/*.kt", fixLeadingSlashStarStar("**/*/*.kt"))
        assertEquals("**.kt", fixLeadingSlashStarStar("**.kt"))
    }
}
