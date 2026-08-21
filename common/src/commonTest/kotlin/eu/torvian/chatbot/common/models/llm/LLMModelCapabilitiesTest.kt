package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the [LLMModelCapabilities.REASONING_ENCRYPTED] capability key and its
 * [LLMModel.isReasoningEncrypted] read helper.
 */
class LLMModelCapabilitiesTest {

    private fun model(capabilities: kotlinx.serialization.json.JsonObject?) = LLMModel(
        id = 1L,
        name = "gpt-5.4",
        providerId = 1L,
        active = true,
        capabilities = capabilities
    )

    @Test
    fun `isReasoningEncrypted returns true when the capability is set to true`() {
        val model = model(
            buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(true))
            }
        )

        assertEquals(true, model.isReasoningEncrypted())
    }

    @Test
    fun `isReasoningEncrypted returns false when the capability is set to false`() {
        val model = model(
            buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(false))
            }
        )

        assertEquals(false, model.isReasoningEncrypted())
    }

    @Test
    fun `isReasoningEncrypted returns null when the capability is absent`() {
        assertNull(model(null).isReasoningEncrypted())
        assertNull(model(buildJsonObject { }).isReasoningEncrypted())
    }

    @Test
    fun `isReasoningEncrypted ignores non-boolean capability values`() {
        val model = model(
            buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive("encrypted"))
            }
        )

        assertNull(model.isReasoningEncrypted())
    }
}
