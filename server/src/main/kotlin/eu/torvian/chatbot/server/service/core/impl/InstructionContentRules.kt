package eu.torvian.chatbot.server.service.core.impl

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Shared content rules for authored instruction content.
 *
 * Every surface that writes an instruction row — the instruction endpoints and the inline specs of a
 * role save — validates through here, so the same input is accepted and rejected with identical
 * wording everywhere. The exact error subtype is decoupled through a factory lambda, letting one
 * implementation serve callers with different error surfaces.
 */
internal object InstructionContentRules {

    /** Maximum allowed length of an instruction name, mirroring agent-role names. */
    const val MAX_NAME_LENGTH: Int = 255

    /**
     * Validates the authored content of one instruction.
     *
     * Rejects an unknown kind (the composer would drop it at read time), a blank or over-long name,
     * and a `model_specific` instruction without a usable `custom.modelId` (the composer keeps only
     * the instance matching the active model, so a missing target would vanish silently).
     *
     * @param type The instruction kind key.
     * @param name The instruction label.
     * @param message The instruction text, or null for the generated-message kind.
     * @param custom Type-specific extra fields.
     * @param raise The raise scope of the caller.
     * @param validationFailed Factory building the caller's validation-failure error.
     */
    fun <E> validate(
        type: String,
        name: String,
        message: String?,
        custom: JsonObject?,
        raise: Raise<E>,
        validationFailed: (reason: String) -> E
    ) = with(raise) {
        ensure(type in AgentInstructionTypes.allKnown) {
            validationFailed("Unknown instruction type '$type'")
        }
        ensure(name.isNotBlank()) {
            validationFailed("Instruction name cannot be blank")
        }
        ensure(name.length <= MAX_NAME_LENGTH) {
            validationFailed("Instruction name cannot exceed $MAX_NAME_LENGTH characters")
        }
        ensure(type != AgentInstructionTypes.MODEL_SPECIFIC || custom.modelIdOrNull() != null) {
            validationFailed("A 'model_specific' instruction must include custom.modelId")
        }
        // The generated-message kind stores no text at all: its message is produced per linked role at
        // read time, so a supplied value is not persisted and must not pretend otherwise.
        ensure(type != AgentInstructionTypes.SPAWNABLE_AGENTS || message.isNullOrEmpty()) {
            validationFailed("A 'spawnable_agents' instruction takes no message")
        }
    }

    /**
     * The value to store in the `message` column: null for the generated-message kind (its text is
     * resolved per linked role at read time), the authored text for every other kind.
     *
     * @param type The instruction kind key.
     * @param message The authored message, or null.
     * @return The value to persist.
     */
    fun storedMessage(type: String, message: String?): String? =
        if (type == AgentInstructionTypes.SPAWNABLE_AGENTS) null else message
}
