package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.text.TextLayoutResult
import eu.torvian.chatbot.app.chat.search.MessageSearchMatch
import kotlin.math.roundToInt


/**
 * Computes the vertical center of the selected search occurrence within laid-out full message text.
 *
 * The returned coordinate is relative to the expanded markdown text block itself. Using the first
 * and last character bounds keeps wrapped occurrences centered in content coordinates.
 *
 * @param textLayoutResult full-content text layout used for geometry lookup.
 * @param selectedSearchMatch selected occurrence whose vertical center should be resolved.
 * @return center Y relative to the expanded markdown text block, or `null` when unavailable.
 */
internal fun computeSelectedSearchMatchCenterY(
    textLayoutResult: TextLayoutResult,
    selectedSearchMatch: MessageSearchMatch,
): Float? {
    val textLength = textLayoutResult.layoutInput.text.length
    if (textLength <= 0 || selectedSearchMatch.startIndex !in 0 until textLength) {
        return null
    }

    val lastMatchCharacterIndex = (selectedSearchMatch.endExclusive - 1)
        .coerceIn(selectedSearchMatch.startIndex, textLength - 1)
    val startBounds = textLayoutResult.getBoundingBox(selectedSearchMatch.startIndex)
    val endBounds = textLayoutResult.getBoundingBox(lastMatchCharacterIndex)
    val top = minOf(startBounds.top, endBounds.top)
    val bottom = maxOf(startBounds.bottom, endBounds.bottom)
    return (top + bottom) / 2f
}

/**
 * Resolves the scroll position that centers the selected occurrence within the visible viewport.
 *
 * Both the input occurrence center and the returned scroll offset are expressed in the scroll
 * container's content coordinate space so [MessageList] can refine scrolling without root geometry.
 *
 * @param selectedOccurrenceCenterYInContent selected occurrence center measured in scroll-content coordinates.
 * @param viewportHeight visible scroll viewport height in pixels.
 * @param maxScroll current maximum scroll offset supported by the container.
 * @return clamped scroll offset that best centers the selected occurrence.
 */
internal fun computeSearchRefinementScrollTarget(
    selectedOccurrenceCenterYInContent: Float,
    viewportHeight: Int,
    maxScroll: Int,
): Int {
    if (viewportHeight <= 0 || maxScroll <= 0) {
        return 0
    }

    val desiredScroll = (selectedOccurrenceCenterYInContent - (viewportHeight / 2f)).roundToInt()
    return desiredScroll.coerceIn(0, maxScroll)
}