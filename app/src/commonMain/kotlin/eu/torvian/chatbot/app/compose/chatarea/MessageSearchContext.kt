package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.app.chat.search.MessageSearchMatch

/**
 * Bundles the in-session search state needed to render one message.
 *
 * @property searchQuery current search query being rendered.
 * @property matches all search occurrences found in the message.
 * @property selectedMatch currently selected occurrence within the same message, if any.
 * @property onSelectedOccurrenceCenterInContentChanged callback that receives the selected
 * occurrence center relative to the rendered message content, or `null` when unavailable.
 */
data class MessageSearchContext(
    val searchQuery: String,
    val matches: List<MessageSearchMatch>,
    val selectedMatch: MessageSearchMatch?,
    val onSelectedOccurrenceCenterInContentChanged: (Float?) -> Unit,
)