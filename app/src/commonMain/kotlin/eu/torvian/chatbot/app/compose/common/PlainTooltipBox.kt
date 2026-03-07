package eu.torvian.chatbot.app.compose.common

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BasicTooltipDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Composable for creating a plain tooltip box.
 *
 * @param text The text to display in the tooltip.
 * @param modifier The modifier to apply to the tooltip box.
 * @param enabled Whether the tooltip should be enabled. Defaults to true.
 * @param caretSize The size of the caret. Defaults to [TooltipDefaults.caretSize].
 * @param spacingBetweenTooltipAndAnchor The spacing between the tooltip and the anchor content. Defaults to 4.dp.
 * @param tooltipDuration The duration for which the tooltip should be shown. Defaults to 3000ms.
 * @param showDelay The delay before the tooltip is shown. Defaults to 0ms.
 * @param content The content to display in the tooltip box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTooltipBox(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    caretSize: DpSize = TooltipDefaults.caretSize,
    spacingBetweenTooltipAndAnchor: Dp = 4.dp,
    tooltipDuration: Long = 3000,
    showDelay: Long = 0L,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        if (enabled) {
            TooltipBox(
                positionProvider = rememberCustomPlainTooltipPositionProvider(spacingBetweenTooltipAndAnchor),
                tooltip = {
                    PlainTooltip(
                    // Note: The parameter caretSize is no longer supported in PlainTooltip. It now uses: caretShape
                    // TODO: update to use caretShape
//                    caretSize = caretSize
                    ) {
                        Text(text)
                    }
                },
                state = rememberCustomTooltipState(
                    tooltipDuration = tooltipDuration,
                    showDelay = showDelay
                ),
                content = content
            )
        } else {
            content()
        }
    }
}


/**
 * [PopupPositionProvider] that should be used with [PlainTooltip]. It correctly positions the
 * tooltip in respect to the anchor content.
 *
 * @param spacingBetweenTooltipAndAnchor the spacing between the tooltip and the anchor content.
 */
@Composable
private fun rememberCustomPlainTooltipPositionProvider(
    spacingBetweenTooltipAndAnchor: Dp
): PopupPositionProvider {
    val tooltipAnchorSpacing =
        with(LocalDensity.current) { spacingBetweenTooltipAndAnchor.roundToPx() }
    return remember(tooltipAnchorSpacing) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2

                // Tooltip prefers to be above the anchor,
                // but if this causes the tooltip to overlap with the anchor
                // then we place it below the anchor
                var y = anchorBounds.top - popupContentSize.height - tooltipAnchorSpacing
                if (y < 0) y = anchorBounds.bottom + tooltipAnchorSpacing

                // Clip the tooltip position to ensure it stays within the window bounds
                val clippedX = x.coerceIn(0, windowSize.width - popupContentSize.width)
                val clippedY = y.coerceIn(0, windowSize.height - popupContentSize.height)

                return IntOffset(clippedX, clippedY)
            }
        }
    }
}

/**
 * Create and remember the default [TooltipState] for [TooltipBox].
 *
 * @param initialIsVisible the initial value for the tooltip's visibility when drawn.
 * @param isPersistent [Boolean] that determines if the tooltip associated with this will be
 *   persistent or not. If isPersistent is true, then the tooltip will only be dismissed when the
 *   user clicks outside the bounds of the tooltip or if [TooltipState.dismiss] is called. When
 *   isPersistent is false, the tooltip will dismiss after a short duration. Ideally, this should be
 *   set to true when there is actionable content being displayed within a tooltip.
 * @param mutatorMutex [MutatorMutex] used to ensure that for all of the tooltips associated with
 *   the mutator mutex, only one will be shown on the screen at any time.
 * @param tooltipDuration The duration for which the tooltip should be shown.
 * @param showDelay The delay before the tooltip is shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@ExperimentalMaterial3Api
@Composable
private fun rememberCustomTooltipState(
    initialIsVisible: Boolean = false,
    isPersistent: Boolean = false,
    mutatorMutex: MutatorMutex = BasicTooltipDefaults.GlobalMutatorMutex,
    tooltipDuration: Long = BasicTooltipDefaults.TooltipDuration,
    showDelay: Long
): TooltipState =
    remember(isPersistent, mutatorMutex, showDelay) {
        TooltipStateImpl(
            initialIsVisible = initialIsVisible,
            isPersistent = isPersistent,
            mutatorMutex = mutatorMutex,
            tooltipDuration = tooltipDuration,
            showDelay = showDelay
        )
    }

/**
 * The default implementation of [TooltipState].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Stable
private class TooltipStateImpl(
    initialIsVisible: Boolean,
    override val isPersistent: Boolean,
    private val mutatorMutex: MutatorMutex,
    private val tooltipDuration: Long,
    private val showDelay: Long
) : TooltipState {
    override val transition: MutableTransitionState<Boolean> =
        MutableTransitionState(initialIsVisible)

    override val isVisible: Boolean
        get() = transition.currentState || transition.targetState

    /** continuation used to clean up */
    private var job: (CancellableContinuation<Unit>)? = null

    /**
     * Show the tooltip associated with the current [TooltipState]. When this method is called, all
     * of the other tooltips associated with [mutatorMutex] will be dismissed.
     *
     * @param mutatePriority [MutatePriority] to be used with [mutatorMutex].
     */

    override suspend fun show(mutatePriority: MutatePriority) {
        val cancellableShow: suspend () -> Unit = {
            delay(showDelay)
            suspendCancellableCoroutine { continuation ->
                transition.targetState = true
                job = continuation
            }
        }

        mutatorMutex.mutate(mutatePriority) {
            try {
                if (isPersistent) {
                    cancellableShow()
                } else {
                    withTimeout(tooltipDuration + showDelay) { cancellableShow() }
                }
            } finally {
                if (mutatePriority != MutatePriority.PreventUserInput) {
                    dismiss()
                }
            }
        }
    }

    /** Dismiss the tooltip associated with this [TooltipState] if it's currently being shown. */
    override fun dismiss() {
        transition.targetState = false
    }

    /** Cleans up [mutatorMutex] when the tooltip associated with this state leaves Composition. */
    override fun onDispose() {
        job?.cancel()
    }
}
