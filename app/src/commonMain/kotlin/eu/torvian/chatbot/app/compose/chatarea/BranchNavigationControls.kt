package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox

/**
 * Displays the branch navigation controls (previous, next, and count).
 *
 * @param branchNavigationData Pre-calculated data for branch navigation.
 * @param onSwitchBranchToMessage Callback to switch to a different thread branch.
 * @param enabled Whether branch switching may run; false while a compaction owns the thread.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
internal fun BranchNavigationControls(
    branchNavigationData: BranchNavigationData,
    onSwitchBranchToMessage: (Long) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Previous branch button '<'
        if (branchNavigationData.showPrev) {
            PlainTooltipBox(text = "Previous branch") {
                IconButton(
                    onClick = {
                        val prevIdx = branchNavigationData.zeroBasedIndex - 1
                        onSwitchBranchToMessage(branchNavigationData.alternativeBranchMessageIds[prevIdx])
                    },
                    modifier = Modifier.size(24.dp),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Previous branch",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Spacer(Modifier.size(24.dp)) // Maintain spacing if button is hidden
        }

        // Current / Total count (1-based for UI)
        Text(
            text = "${branchNavigationData.zeroBasedIndex + 1} / ${branchNavigationData.totalBranches}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp) // Small padding around text
        )

        // Next branch button '>'
        if (branchNavigationData.showNext) {
            PlainTooltipBox(text = "Next branch") {
                IconButton(
                    onClick = {
                        val nextIdx = branchNavigationData.zeroBasedIndex + 1
                        onSwitchBranchToMessage(branchNavigationData.alternativeBranchMessageIds[nextIdx])
                    },
                    modifier = Modifier.size(24.dp),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Next branch",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Spacer(Modifier.size(24.dp)) // Maintain spacing if button is hidden
        }
    }
}
