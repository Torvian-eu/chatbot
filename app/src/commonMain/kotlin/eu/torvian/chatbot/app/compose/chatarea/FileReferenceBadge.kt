package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.core.FileReference

/**
 * Displays a clickable badge for a file reference.
 * Shows the file name and indicates if content is included.
 *
 * @param fileReference The file reference to display
 * @param onClick Called when the badge is clicked to show file details
 * @param modifier Optional modifier
 */
@Composable
fun FileReferenceBadge(
    fileReference: FileReference,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasContent = fileReference.content != null
    val color = if (hasContent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hasContent) Icons.Default.Description else Icons.Default.AttachFile,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )

            Text(
                text = fileReference.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Displays a clickable badge for a file reference with an optional remove button.
 * Shows the file name and indicates if content is included.
 *
 * @param fileReference The file reference to display
 * @param onClick Called when the badge is clicked to show file details
 * @param onRemove Called when the remove button is clicked (optional)
 * @param modifier Optional modifier
 */
@Composable
fun RemovableFileReferenceBadge(
    fileReference: FileReference,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasContent = fileReference.content != null
    val color = if (hasContent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hasContent) Icons.Default.Description else Icons.Default.AttachFile,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )

            Text(
                text = fileReference.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(18.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove file",
                        tint = color.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Displays a horizontal row of file reference badges.
 * Wraps to multiple lines if needed.
 *
 * @param fileReferences List of file references to display
 * @param onFileReferenceClick Called when a badge is clicked
 * @param modifier Optional modifier
 */
@Composable
fun FileReferenceBadgeRow(
    fileReferences: List<FileReference>,
    onFileReferenceClick: (FileReference) -> Unit,
    modifier: Modifier = Modifier
) {
    if (fileReferences.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        fileReferences.forEach { fileReference ->
            FileReferenceBadge(
                fileReference = fileReference,
                onClick = { onFileReferenceClick(fileReference) }
            )
        }
    }
}

