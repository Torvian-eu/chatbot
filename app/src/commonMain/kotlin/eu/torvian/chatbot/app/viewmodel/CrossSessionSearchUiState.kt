package eu.torvian.chatbot.app.viewmodel

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope

/**
 * Cohesive UI state for the cross-session search dialog.
 *
 * The dialog keeps editable request inputs separate from the submitted request metadata so users can
 * close and reopen the dialog, or adjust the draft query, without losing the previously loaded
 * result set context.
 *
 * @property isDialogVisible Whether the dialog should currently be rendered.
 * @property draftQuery Editable query currently shown in the text field.
 * @property draftScope Scope currently selected in the dialog controls.
 * @property submittedQuery Query that produced [resultsState].
 * @property submittedScope Scope that produced [resultsState].
 * @property resultsState Latest repository-backed result state shown in the dialog.
 */
data class CrossSessionSearchUiState(
    val isDialogVisible: Boolean = false,
    val draftQuery: String = "",
    val draftScope: MessageSearchScope = MessageSearchScope.VISIBLE_THREADS_ONLY,
    val submittedQuery: String = "",
    val submittedScope: MessageSearchScope = MessageSearchScope.VISIBLE_THREADS_ONLY,
    val resultsState: DataState<RepositoryError, List<MessageSearchResult>> = DataState.Idle,
) {
    /**
     * Query that should be restored into in-session search when a result is selected.
     *
     * Submitted text takes precedence so draft edits made after the last search do not change where
     * cross-session navigation lands.
     */
    val navigationQuery: String
        get() = submittedQuery.ifBlank { draftQuery }.trim()
}