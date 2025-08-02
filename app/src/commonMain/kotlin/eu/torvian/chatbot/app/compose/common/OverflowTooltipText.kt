package eu.torvian.chatbot.app.compose.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * A Text composable that displays a PlainTooltip when its content overflows.
 * The tooltip will show the full text.
 *
 * @param text The text content to display and use for the tooltip.
 * @param modifier The modifier to be applied to the underlying Text and TooltipBox.
 * @param style The [TextStyle] to apply to the text.
 * @param color The color to apply to the text.
 * @param maxLines The maximum number of lines for the text to occupy. Defaults to 1.
 *                 For overflow to occur, this must be less than [Int.MAX_VALUE].
 * @param overflow How visual overflow should be handled. Defaults to [TextOverflow.Ellipsis].
 * @param caretSize The size of the tooltip caret.
 * @param spacingBetweenTooltipAndAnchor The spacing between the tooltip and the text content.
 * @param tooltipDuration The duration for which the tooltip should be shown. Defaults to 3000ms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverflowTooltipText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    caretSize: DpSize = TooltipDefaults.caretSize,
    spacingBetweenTooltipAndAnchor: Dp = 4.dp,
    tooltipDuration: Long = 3000
) {
    var didTextOverflow by remember { mutableStateOf(false) }

    PlainTooltipBox(
        text = text, // The tooltip text is the same as the displayed text
        modifier = modifier,
        enabled = didTextOverflow, // Only enable the tooltip if the text overflowed
        caretSize = caretSize,
        spacingBetweenTooltipAndAnchor = spacingBetweenTooltipAndAnchor,
        tooltipDuration = tooltipDuration
    ) {
        Text(
            text = text,
            // Modifier is applied to the PlainTooltipBox, which contains this Text.
            // We usually don't need a separate modifier here unless for inner padding/sizing.
            // In this case, the parent's modifier handles the layout weight.
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { textLayoutResult ->
                // Check if the text actually overflowed
                val currentTextLength = textLayoutResult.layoutInput.text.length
                val visibleCharactersOnFirstLine = textLayoutResult.getLineEnd(lineIndex = 0, visibleEnd = true)
                val isTruncated = visibleCharactersOnFirstLine < currentTextLength

                // For multi-line text, you might need a more complex check,
                // but for maxLines=1 and Ellipsis, this is generally sufficient.
                didTextOverflow = isTruncated || textLayoutResult.didOverflowHeight
            }
        )
    }
}