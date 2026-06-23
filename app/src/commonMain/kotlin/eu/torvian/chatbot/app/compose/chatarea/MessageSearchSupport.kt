package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Returns concrete search occurrences found in the displayed messages.
 *
 * Matching is case-insensitive, preserves active-branch display order, and emits one result per
 * occurrence so navigation can distinguish repeated hits inside the same message. Blank queries
 * intentionally produce no results so the UI can treat search as inactive.
 *
 * @param messages messages currently shown in the active branch.
 * @param query user-entered search query.
 * @return concrete search matches in display order.
 */
internal fun findSearchMatches(messages: List<ChatMessage>, query: String): List<MessageSearchMatch> {
    if (query.isBlank()) {
        return emptyList()
    }
    return buildList {
        messages.forEach { message ->
            findSearchMatchRanges(message.content, query).forEachIndexed { occurrenceIndex, matchRange ->
                add(
                    MessageSearchMatch(
                        messageId = message.id,
                        occurrenceIndexInMessage = occurrenceIndex,
                        startIndex = matchRange.first,
                        endExclusive = matchRange.last + 1,
                    )
                )
            }
        }
    }
}

/**
 * Normalizes a requested result index against the current search results.
 *
 * An empty result set always maps to `-1`. Non-empty results clamp invalid selections to the
 * first result so new queries immediately pick a stable target.
 *
 * @param searchResults ordered matching occurrences.
 * @param requestedIndex index requested by the UI.
 * @return `-1` when there are no results, otherwise a valid index within the result set.
 */
internal fun normalizeSearchIndex(searchResults: List<MessageSearchMatch>, requestedIndex: Int): Int = when {
    searchResults.isEmpty() -> -1
    requestedIndex in searchResults.indices -> requestedIndex
    else -> 0
}

/**
 * Computes the next selected search result index for the given navigation direction.
 *
 * Navigation wraps around the result set to support continuous cycling.
 *
 * @param searchResults ordered matching occurrences.
 * @param currentIndex currently selected result index.
 * @param direction requested navigation direction.
 * @return the next selected index, or `-1` when there are no matches.
 */
internal fun navigateSearchIndex(
    searchResults: List<MessageSearchMatch>,
    currentIndex: Int,
    direction: SearchDirection,
): Int {
    if (searchResults.isEmpty()) {
        return -1
    }
    val normalizedIndex = normalizeSearchIndex(searchResults, currentIndex)
    return when (direction) {
        SearchDirection.BACKWARD -> (normalizedIndex - 1 + searchResults.size) % searchResults.size
        SearchDirection.FORWARD -> (normalizedIndex + 1) % searchResults.size
    }
}

/**
 * Finds non-overlapping ranges for the query within the provided text.
 *
 * The ranges are returned using inclusive bounds so they can be converted directly into Compose
 * span ranges by adding one to the end index.
 *
 * @param text source text that should be highlighted.
 * @param query search query to locate.
 * @return non-overlapping inclusive match ranges in source order.
 */
internal fun findSearchMatchRanges(text: String, query: String): List<IntRange> {
    if (text.isEmpty() || query.isBlank()) {
        return emptyList()
    }

    val matches = mutableListOf<IntRange>()
    var searchStartIndex = 0

    while (searchStartIndex < text.length) {
        val matchStartIndex = text.indexOf(query, startIndex = searchStartIndex, ignoreCase = true)
        if (matchStartIndex < 0) {
            break
        }

        val matchEndIndex = matchStartIndex + query.length - 1
        matches += matchStartIndex..matchEndIndex
        // Advance by the query length to avoid overlapping highlight ranges.
        searchStartIndex = matchEndIndex + 1
    }

    return matches
}
