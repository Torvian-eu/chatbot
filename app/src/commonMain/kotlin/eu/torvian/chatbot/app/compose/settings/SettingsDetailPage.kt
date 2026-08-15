package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper

/**
 * Shared shell for settings detail pages.
 *
 * The shell keeps the navigation affordance, page category label, item title,
 * and optional trailing actions aligned so each details page presents the same
 * top-level structure regardless of the underlying content body.
 *
 * @param categoryName Human-readable category label shown above the item title.
 * @param itemName Title of the selected settings item.
 * @param supportingText Optional secondary line shown beneath the item title.
 * @param onBackToList Callback invoked when the user returns to the previous list.
 * @param backContentDescription Accessible description for the back affordance.
 * @param modifier Modifier applied to the outer surface.
 * @param actions Optional trailing action content shown in the header.
 * @param content Scrollable or fixed page body shown below the header divider.
 */
@Composable
fun SettingsDetailPage(
    categoryName: String,
    itemName: String,
    onBackToList: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        tonalElevation = 1.dp
    ) {
        val scrollState = rememberScrollState()

        // The desktop scrollbar tracks the shared scroll state so every detail page gets a visible
        // scroll indicator (Android/Web fall back to their native scrollbars via ScrollbarWrapper).
        ScrollbarWrapper(
            scrollState = scrollState,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBackToList) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backContentDescription
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = itemName,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!supportingText.isNullOrBlank()) {
                            Text(
                                text = supportingText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }

                HorizontalDivider()

                Box(modifier = Modifier.fillMaxWidth()) {
                    content()
                }
            }
        }
    }
}

