package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.isReasoningEncrypted
import eu.torvian.chatbot.server.service.core.LLMModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Records whether a model delivers its reasoning as encrypted (`encrypted_content`) or plaintext
 * (`content`) payloads, as the [LLMModelCapabilities.REASONING_ENCRYPTED] capability on the model.
 *
 * The capability is auto-detected from actually observed reasoning items (never seeded from
 * provider/model metadata) and persisted **exactly once**: when the capability is already known
 * (non-null) detection is skipped entirely and the recorded value is trusted as correct. This keeps
 * detection cheap (one-time per model) and prevents flapping if a provider changes behavior.
 *
 * The recorder is invoked from the turn orchestrator after each assistant response; a no-op when the
 * model produced no reasoning items, when the items are inconclusive, or when the capability is
 * already recorded.
 */
interface ReasoningCapabilityRecorder {
    /**
     * Detects and persists the producing model's reasoning mode from its observed reasoning items.
     *
     * @param model The model that produced [reasoningItems].
     * @param reasoningItems The raw reasoning output items from the model's response, or
     *            `null`/empty when there are none.
     */
    suspend fun record(model: LLMModel, reasoningItems: List<JsonObject>?)
}

/**
 * Default [ReasoningCapabilityRecorder] that persists the detected reasoning mode through the
 * [LLMModelService].
 *
 * @property llmModelService Service used to persist the capability update on the model record.
 */
class DefaultReasoningCapabilityRecorder(
    private val llmModelService: LLMModelService,
) : ReasoningCapabilityRecorder {

    private val logger: Logger = LogManager.getLogger(DefaultReasoningCapabilityRecorder::class.java)

    override suspend fun record(model: LLMModel, reasoningItems: List<JsonObject>?) {
        // The capability is recorded once and then trusted; never re-detect or overwrite a known value.
        if (model.isReasoningEncrypted() != null) return
        val encrypted = detectReasoningEncryption(reasoningItems) ?: return // not certain — don't persist
        val caps = (model.capabilities ?: JsonObject(emptyMap())).toMutableMap()
        caps[LLMModelCapabilities.REASONING_ENCRYPTED] = JsonPrimitive(encrypted)
        llmModelService.updateModel(model.copy(capabilities = JsonObject(caps)))
            .fold(
                ifLeft = { error ->
                    logger.warn("Failed to record reasoning encryption for model ${model.id}: $error")
                },
                ifRight = { /* no-op */ }
            )
    }
}
