package eu.torvian.chatbot.server.service.llm

import arrow.core.right
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.server.service.core.LLMModelService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * Verifies [DefaultReasoningCapabilityRecorder] detects and persists a model's reasoning mode
 * (encrypted vs plaintext) exactly once, and only when the observed items are decisive.
 */
class ReasoningCapabilityRecorderTest {

    private val llmModelService: LLMModelService = mockk()

    private val recorder = DefaultReasoningCapabilityRecorder(llmModelService)

    private val baseModel = LLMModel(
        id = 1L,
        name = "gpt-5.4",
        providerId = 1L,
        active = true,
        displayName = "GPT-5.4"
    )

    private fun reasoningItem(
        encryptedContent: String? = null,
        plaintextContent: Boolean = false
    ): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_1")
        if (encryptedContent != null) {
            put("encrypted_content", encryptedContent)
        }
        if (plaintextContent) {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
        }
    }

    @Test
    fun `persists encrypted mode when the capability is unknown and encrypted content is observed`() = runTest {
        val model = baseModel
        val reasoningItems = listOf(reasoningItem(encryptedContent = "opaque"))
        coEvery { llmModelService.updateModel(any()) } returns Unit.right()

        recorder.record(model, reasoningItems)

        coVerify(exactly = 1) {
            llmModelService.updateModel(
                match { updated ->
                    updated.capabilities
                        ?.get(LLMModelCapabilities.REASONING_ENCRYPTED)
                        ?.jsonPrimitive?.boolean == true
                }
            )
        }
    }

    @Test
    fun `persists plaintext mode when plaintext content is observed`() = runTest {
        val model = baseModel
        val reasoningItems = listOf(reasoningItem(plaintextContent = true))
        coEvery { llmModelService.updateModel(any()) } returns Unit.right()

        recorder.record(model, reasoningItems)

        coVerify(exactly = 1) {
            llmModelService.updateModel(
                match { updated ->
                    updated.capabilities
                        ?.get(LLMModelCapabilities.REASONING_ENCRYPTED)
                        ?.jsonPrimitive?.boolean == false
                }
            )
        }
    }

    @Test
    fun `skips detection when the capability is already known`() = runTest {
        val model = baseModel.copy(
            capabilities = buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(false))
            }
        )
        val reasoningItems = listOf(reasoningItem(encryptedContent = "opaque"))

        recorder.record(model, reasoningItems)

        // A known value is trusted as correct and must never be overwritten, even by conflicting evidence.
        coVerify(exactly = 0) { llmModelService.updateModel(any()) }
    }

    @Test
    fun `skips persistence when no reasoning items were observed`() = runTest {
        val model = baseModel

        recorder.record(model, null)
        recorder.record(model, emptyList())

        coVerify(exactly = 0) { llmModelService.updateModel(any()) }
    }

    @Test
    fun `skips persistence when the items are inconclusive`() = runTest {
        val model = baseModel
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("summary", buildJsonArray { })
            }
        )

        recorder.record(model, reasoningItems)

        coVerify(exactly = 0) { llmModelService.updateModel(any()) }
    }

    @Test
    fun `ignores a null encrypted_content value when detecting`() = runTest {
        val model = baseModel
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("encrypted_content", null)
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", "Chain of thought.")
                    })
                })
            }
        )
        coEvery { llmModelService.updateModel(any()) } returns Unit.right()

        recorder.record(model, reasoningItems)

        coVerify(exactly = 1) {
            llmModelService.updateModel(
                match { updated ->
                    updated.capabilities
                        ?.get(LLMModelCapabilities.REASONING_ENCRYPTED)
                        ?.jsonPrimitive?.boolean == false
                }
            )
        }
        confirmVerified(llmModelService)
    }
}
