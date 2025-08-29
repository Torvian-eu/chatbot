package eu.torvian.chatbot.app.compose.sessionlist

import eu.torvian.chatbot.common.models.ChatGroup

/**
 * Data class to encapsulate group editing related actions.
 */
data class GroupEditingActions(
    val onUpdateEditingGroupNameInput: (String) -> Unit,
    val onSaveRenamedGroup: () -> Unit,
    val onCancelRenamingGroup: () -> Unit,
    val onStartRenamingGroup: (ChatGroup) -> Unit,
)