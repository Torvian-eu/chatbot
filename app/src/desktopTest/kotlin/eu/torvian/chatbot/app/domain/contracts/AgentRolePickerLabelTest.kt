package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the chip label composition shared by the grouped role pickers.
 */
class AgentRolePickerLabelTest {

    /**
     * @param name Role machine name.
     * @param displayName Optional operator-facing name.
     * @return A role carrying only the fields the label reads.
     */
    private fun role(name: String, displayName: String? = null) = AgentRoleDto(
        id = 1L,
        name = name,
        displayName = displayName,
        modelId = null,
        modelSettingsId = null
    )

    /** A role without a display name is labelled by its machine name alone. */
    @Test
    fun `label - falls back to the machine name`() {
        assertEquals("writer", role("writer").pickerLabel)
    }

    /** A blank display name is treated as absent rather than rendered after the separator. */
    @Test
    fun `label - ignores a blank display name`() {
        assertEquals("writer", role("writer", displayName = "   ").pickerLabel)
    }

    /** A set display name follows the machine name after an em dash. */
    @Test
    fun `label - appends the display name`() {
        assertEquals("writer — Scribe", role("writer", displayName = "Scribe").pickerLabel)
    }

    /** The composition only prefixes the name, so an embedded em dash is passed through unchanged. */
    @Test
    fun `label - keeps an em dash inside the name`() {
        assertEquals("writer — Scribe — v2", role("writer — Scribe", displayName = "v2").pickerLabel)
    }
}
