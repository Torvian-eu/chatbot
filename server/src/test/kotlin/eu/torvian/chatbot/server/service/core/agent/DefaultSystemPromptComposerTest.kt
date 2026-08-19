package eu.torvian.chatbot.server.service.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DefaultSystemPromptComposer]'s per-model instruction filtering.
 *
 * Verifies that [ModelSpecificInstruction] entries participate only when the conversation's role
 * model matches their target, that a null role model omits all of them, and that other kinds always
 * participate.
 */
class DefaultSystemPromptComposerTest {

    private val composer = DefaultSystemPromptComposer()

    @Test
    fun `only the model_specific instance matching the role model is composed`() = runTest {
        val instructions = listOf(
            CustomInstruction("Tone", "Be concise"),
            ModelSpecificInstruction("Swift mode", "Write idiomatic Swift", modelId = 2L),
            ModelSpecificInstruction("Kotlin mode", "Write idiomatic Kotlin", modelId = 3L)
        )
        val role = AgentRole(
            id = 1L,
            name = "test",
            modelId = 3L,
            modelSettingsId = 1L,
            instructions = instructions
        )

        val prompt = composer.compose(role)

        assertTrue(prompt.contains("Be concise"), "static kinds must always participate")
        assertTrue(prompt.contains("Write idiomatic Kotlin"), "matching model_specific must participate")
        assertFalse(prompt.contains("Write idiomatic Swift"), "non-matching model_specific must be omitted")
    }

    @Test
    fun `model_specific instructions are omitted when the role model is null`() = runTest {
        val instructions = listOf(
            CustomInstruction("Tone", "Be concise"),
            ModelSpecificInstruction("Swift mode", "Write idiomatic Swift", modelId = 2L)
        )
        val role = AgentRole(
            id = 1L,
            name = "test",
            modelId = null,
            modelSettingsId = 1L,
            instructions = instructions
        )

        val prompt = composer.compose(role)

        assertEquals("Be concise", prompt)
    }

    @Test
    fun `model_specific never matches when the role runs on a different model`() = runTest {
        val instructions = listOf(
            ModelSpecificInstruction("Swift mode", "Write idiomatic Swift", modelId = 2L)
        )
        val role = AgentRole(
            id = 1L,
            name = "test",
            modelId = 1L,
            modelSettingsId = 1L,
            instructions = instructions
        )

        val prompt = composer.compose(role)

        assertEquals("", prompt)
    }
}
