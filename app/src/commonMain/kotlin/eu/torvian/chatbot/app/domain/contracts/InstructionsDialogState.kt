package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto

/**
 * Dialog contract of the Instructions tab.
 */
sealed class InstructionsDialogState {

    /** No dialog is currently visible. */
    object None : InstructionsDialogState()

    /** Delete confirmation for one instruction. */
    data class DeleteInstruction(
        val instruction: AgentInstructionDto
    ) : InstructionsDialogState()

    /**
     * Content editor for one instruction.
     *
     * Only the authored content is editable: the kind and the target model are part of the row's
     * role-level validity, which the library cannot judge without the linked roles' lists, so they stay
     * with the agent-role editor. The edited content is shared, so saving reaches every role that links
     * the row.
     *
     * @property instruction The row being edited, prefilled into [name] and [message].
     * @property name The edited label.
     * @property message The edited text. A row of the generated-message kind stores none.
     */
    data class EditInstruction(
        val instruction: AgentInstructionDto,
        val name: String = instruction.name,
        val message: String = instruction.message
    ) : InstructionsDialogState() {

        /** Whether the edited label is blank, which the server rejects. */
        val isNameBlank: Boolean get() = name.isBlank()

        /** Whether the form holds a writable change, so saving has something to send. */
        val hasChanges: Boolean get() = name != instruction.name || message != instruction.message

        /** Whether the form can be saved: a non-blank label and at least one edit. */
        val canSave: Boolean get() = !isNameBlank && hasChanges
    }
}
