package eu.torvian.chatbot.app.chat.search

import eu.torvian.chatbot.common.models.core.ChatMessage

/** Maximum number of matches to return from a single search call. Protects against
 * pathological result explosions while still covering realistic in-thread searches. */
internal const val DEFAULT_MAX_MATCHES = 100

/** Minimum query length required to trigger expensive search matching. Single-character
 * queries return no results to avoid scanning every message unnecessarily. */
internal const val MIN_QUERY_LENGTH = 2

/**
 * Returns concrete search occurrences found in the displayed messages.
 *
 * Matching is case-insensitive, preserves active-branch display order, and emits one result per
 * occurrence so navigation can distinguish repeated hits inside the same message. The scan
 * is performed in a single pass per message using an inline while-loop that checks
 * the [maxMatches] cap after each discovery, so no labeled break or deferred
 * range collection is needed. Blank queries and queries shorter than [MIN_QUERY_LENGTH]
 * intentionally produce no results to avoid pathological result explosions and reduce CPU
 * waste on meaningless searches.
 *
 * @param messages messages currently shown in the active branch.
 * @param query user-entered search query.
 * @param maxMatches maximum number of matches to return (default [DEFAULT_MAX_MATCHES]).
 * @return concrete search matches in display order, capped at [maxMatches].
 */
internal fun findSearchMatches(
    messages: List<ChatMessage>,
    query: String,
    maxMatches: Int = DEFAULT_MAX_MATCHES,
): List<MessageSearchMatch> {
    if (query.isBlank() || query.length < MIN_QUERY_LENGTH) {
        // Skip expensive matching for queries shorter than 2 characters — this prevents
        // every single letter from triggering a full message scan, keeping typing responsive.
        return emptyList()
    }

    val results = mutableListOf<MessageSearchMatch>()

    for (message in messages) {
        // Stop scanning additional messages once the limit is reached.
        if (results.size >= maxMatches) break

        val content = message.content
        var occurrenceIndex = 0
        var searchStartIndex = 0

        while (searchStartIndex < content.length && results.size < maxMatches) {
            val matchStart = content.indexOf(query, startIndex = searchStartIndex, ignoreCase = true)
            if (matchStart < 0) break
            val endExclusive = matchStart + query.length
            results.add(
                MessageSearchMatch(
                    messageId = message.id,
                    occurrenceIndexInMessage = occurrenceIndex,
                    startIndex = matchStart,
                    endExclusive = endExclusive,
                )
            )
            occurrenceIndex++
            // Advance by the query length to avoid overlapping highlights.
            searchStartIndex = endExclusive
        }
    }

    return results
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
